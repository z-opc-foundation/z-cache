#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
PROJECT_NAME="${PROJECT_NAME:-z-cache}"
DOCKERFILE_PATH="${SCRIPT_DIR}/Dockerfile"
IMAGE_NAME="${IMAGE_NAME:-${PROJECT_NAME}:latest}"
CONTAINER_NAME="${CONTAINER_NAME:-${PROJECT_NAME}}"
HOST_PORT="${HOST_PORT:-6379}"
CONTAINER_PORT=6379
HEALTH_CHECK_TIMEOUT="${HEALTH_CHECK_TIMEOUT:-60}"
OLD_CONTAINER_NAME=""

log() {
    echo "[$(date +'%Y-%m-%d %H:%M:%S')] $1"
}

check_docker_status() {
    docker info >/dev/null 2>&1 || {
        log "错误：Docker 服务未运行，请先启动 Docker。"
        exit 1
    }
}

package() {
    mvn -f "${SCRIPT_DIR}/pom.xml" -pl z-cache-server -am clean package -DskipTests
}

build_image() {
    log "开始构建 Docker 镜像：${IMAGE_NAME}"
    local build_args=( -f "${DOCKERFILE_PATH}" -t "${IMAGE_NAME}" "${SCRIPT_DIR}/.." )
    local host_m2="${HOST_M2:-${HOME}/.m2}"
    if [[ -d "${host_m2}" ]]; then
        build_args=(
            --build-context "maven-m2=${host_m2}"
            "${build_args[@]}"
        )
        log "使用主机 Maven 仓库上下文：${host_m2}"
    else
        log "警告：未找到主机 Maven 仓库 ${host_m2}，构建时将重新下载依赖。"
    fi
    docker buildx build "${build_args[@]}"
}

stop_old_container() {
    if docker ps -aq --filter "name=^/${CONTAINER_NAME}$" | grep -q .; then
        OLD_CONTAINER_NAME="${CONTAINER_NAME}-old"
        docker rm -f "${OLD_CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rename "${CONTAINER_NAME}" "${OLD_CONTAINER_NAME}"
        docker stop "${OLD_CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi
}

cleanup_old_container() {
    if [[ -n "${OLD_CONTAINER_NAME}" ]]; then
        docker rm -f "${OLD_CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi
}

restore_old_container() {
    if [[ -n "${OLD_CONTAINER_NAME}" ]] && docker ps -aq --filter "name=^/${OLD_CONTAINER_NAME}$" | grep -q .; then
        docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
        docker rename "${OLD_CONTAINER_NAME}" "${CONTAINER_NAME}"
        docker start "${CONTAINER_NAME}" >/dev/null 2>&1 || true
    fi
}

start_container() {
    log "启动容器：${CONTAINER_NAME}"
    if ! docker run -d \
        --name "${CONTAINER_NAME}" \
        -p "${HOST_PORT}:${CONTAINER_PORT}" \
        -e "ZCACHE_PORT=${CONTAINER_PORT}" \
        -e "ZCACHE_MAX_ENTRIES=${ZCACHE_MAX_ENTRIES:-0}" \
        -e "ZCACHE_PASSWORD=${ZCACHE_PASSWORD:-}" \
        -e "ZCACHE_PASSWORD_FILE=${ZCACHE_PASSWORD_FILE:-}" \
        --restart=unless-stopped \
        "${IMAGE_NAME}" >/dev/null; then
        restore_old_container
        return 1
    fi
}

health_check() {
    log "等待服务健康检查通过（${HEALTH_CHECK_TIMEOUT}秒）"
    for ((i = 0; i < HEALTH_CHECK_TIMEOUT; i++)); do
        status=$(docker inspect --format='{{.State.Health.Status}}' "${CONTAINER_NAME}" 2>/dev/null || true)
        if [[ "${status}" == "healthy" ]]; then
            log "健康检查通过。"
            return 0
        fi
        if [[ "${status}" == "unhealthy" ]]; then
            docker logs "${CONTAINER_NAME}" || true
            restore_old_container
            return 1
        fi
        sleep 1
    done
    docker logs "${CONTAINER_NAME}" || true
    restore_old_container
    log "错误：健康检查超时，已清理新容器并恢复旧容器（如存在）。"
    return 1
}

usage() {
    echo "用法：$0 [build|deploy|logs|stop]"
}

main() {
    local action="${1:-deploy}"
    case "${action}" in
        build)
            check_docker_status
            package
            build_image
            ;;
        deploy)
            check_docker_status
            package
            build_image
            stop_old_container
            start_container
            health_check
            cleanup_old_container
            ;;
        logs)
            docker logs -f "${CONTAINER_NAME}"
            ;;
        stop)
            docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
            ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
