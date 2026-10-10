import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
    plugins: [react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] ,
        alias: process.env.LOCAL_SIBLINGS === '1' ? { '@yuku123/z-cache-component': '../z-cache-component/src' } : {}},
    server: {
        port: 3026,
        fs: {allow: ['..']},
        proxy: {'/cache': {target: 'http://localhost:8888', changeOrigin: true}, '/actuator': {target: 'http://localhost:8888', changeOrigin: true}},
    },
})
