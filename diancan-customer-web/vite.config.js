import {defineConfig} from 'vite';
export default defineConfig({base:'/order/',server:{host:'127.0.0.1',port:9530,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:8080',changeOrigin:false},'/__images':{target:'http://127.0.0.1:19000',changeOrigin:true,rewrite:p=>p.replace('/__images','')}}},build:{target:'es2019'}});
