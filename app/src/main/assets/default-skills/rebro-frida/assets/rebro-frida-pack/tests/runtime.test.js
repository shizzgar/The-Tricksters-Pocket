#!/usr/bin/env node
'use strict';
/* Offline contracts, not an Android/ART emulator. No dependency beyond Node. */
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'..'), catalog=JSON.parse(fs.readFileSync(path.join(root,'catalog.json')));
let passed=0;
function test(name,fn){fn();passed++;process.stdout.write('PASS '+name+'\n');}
function harness(names=[],opts={},limits={},java=true){
 const events=[],memory=Buffer.alloc(1<<20),hooks=[],observers=[],classes=new Map(),followed=new Map();
 const state={events,hooks,observers,classes,memory};
 class Pointer{
  constructor(n){this.n=Number(n instanceof Pointer?n.n:n);}
  add(n){return new Pointer(this.n+Number(n));}sub(n){return new Pointer(this.n-Number(n instanceof Pointer?n.n:n));}
  compare(p){return Math.sign(this.n-p.n);}isNull(){return this.n===0;}toString(){return '0x'+this.n.toString(16);}
  toUInt32(){return this.n>>>0;}toInt32(){return this.n|0;}
  readU8(){return memory.readUInt8(this.n);}readU16(){return memory.readUInt16LE(this.n);}
  readPointer(){return new Pointer(memory.readBigUInt64LE(this.n));}
  readCString(n=256){const b=memory.subarray(this.n,this.n+n),z=b.indexOf(0);return b.subarray(0,z<0?b.length:z).toString();}
  readByteArray(n){return Uint8Array.from(memory.subarray(this.n,this.n+n)).buffer;}
 }
 const ptr=n=>new Pointer(n),symbolNames=['open','openat','connect','sendto','pthread_create','dlopen','android_dlopen_ext','test'];
 const symbols=Object.fromEntries(symbolNames.map((x,i)=>[x,ptr(0x1100+i*16)]));
 const mod={name:'libc.so',path:'/system/lib64/libc.so',base:ptr(0x1000),size:0x8000,
 findExportByName:n=>symbols[n]||null,
 enumerateExports:()=>symbolNames.map(n=>({name:n,type:'function',address:symbols[n]})),
 enumerateImports:()=>[{name:'fixture',type:'function',module:'libdl.so',address:ptr(0x1010)}],
 enumerateSymbols:()=>[{name:'local',type:'function',address:ptr(0x1100),size:16}]};
 state.modules=[mod];state.symbols=symbols;state.ptr=ptr;
 function ov(types=[]){
  const x={argumentTypes:types.map(className=>({className})),result:17,error:null,
   apply(receiver,args){x.last={receiver,args};if(x.error)throw x.error;return x.result;}};
  let token=null;
  Object.defineProperty(x,'implementation',{get(){return token;},set(fn){token=fn===null?null:function(){return fn.apply(this,arguments);};}});
  return x;
 }
 const specifications={
  'java.lang.ClassLoader':{loadClass:[['java.lang.String'],['java.lang.String','boolean']]},
  'java.lang.reflect.Method':{invoke:[['java.lang.Object','[Ljava.lang.Object;']]},
  'dalvik.system.DexClassLoader':{$init:[['java.lang.String','java.lang.String','java.lang.String','java.lang.ClassLoader']]},
  'dalvik.system.InMemoryDexClassLoader':{$init:[['java.nio.ByteBuffer','java.lang.ClassLoader']]},
  'java.lang.Thread':{start:[[]]},
  'android.app.Activity':{onResume:[[]],onPause:[[]],onDestroy:[[]]},
  'android.content.ContextWrapper':{startActivity:[['android.content.Intent']],startService:[['android.content.Intent']],sendBroadcast:[['android.content.Intent']]},
  'android.webkit.WebView':{loadUrl:[['java.lang.String']],addJavascriptInterface:[['java.lang.Object','java.lang.String']],evaluateJavascript:[['java.lang.String','android.webkit.ValueCallback']]},
  'android.app.SharedPreferencesImpl':Object.fromEntries(['getString','getInt','getLong','getBoolean','getFloat','contains'].map(n=>[n,[['java.lang.String']]])),
  'android.app.SharedPreferencesImpl$EditorImpl':Object.fromEntries(['putString','putInt','putLong','putBoolean','remove'].map(n=>[n,[['java.lang.String']]])),
  'android.database.sqlite.SQLiteDatabase':{execSQL:[['java.lang.String'],['java.lang.String','[Ljava.lang.Object;']],rawQuery:[['java.lang.String','[Ljava.lang.String;']]},
  'java.io.FileInputStream':{$init:[['java.lang.String'],['java.io.File']]},
  'java.io.FileOutputStream':{$init:[['java.lang.String'],['java.io.File']]},
  'java.net.URL':{openConnection:[[],['java.net.Proxy']]},
  'okhttp3.OkHttpClient':{newCall:[['okhttp3.Request']]},
  'javax.crypto.Cipher':{getInstance:[['java.lang.String']],init:[['int','java.security.Key']],doFinal:[[],['[B']]},
  'java.security.MessageDigest':{getInstance:[['java.lang.String']],digest:[[],['[B']]},
  'javax.crypto.Mac':{getInstance:[['java.lang.String']],doFinal:[[],['[B']]},
  'android.os.BinderProxy':{transact:[['int','android.os.Parcel','android.os.Parcel','int']]},
  'android.content.res.AssetManager':{open:[['java.lang.String']]},
  'com.example.Fixture':{calculate:[['int'],['java.lang.String']]}
 };
 function use(name){
  if(classes.has(name))return classes.get(name);
  let klass;
  if(name==='android.os.Build')klass={MODEL:{value:'fixture'}};
  else if(name==='android.os.Build$VERSION')klass={SDK_INT:{value:36}};
  else{
   if(!specifications[name])throw new Error('Mock class missing '+name);
   klass={$className:name,class:{getDeclaredMethods:()=>[{toString:()=>name+'.calculate(int)'}]}};
   for(const [method,signatures]of Object.entries(specifications[name]))klass[method]={overloads:signatures.map(ov)};
  }
  classes.set(name,klass);return klass;
 }
 const loader={$className:'dalvik.system.PathClassLoader',toString:()=> 'fixture loader'};
 const bridge={available:true,androidVersion:'16',perform:f=>f(),performNow:f=>f(),use,
  classFactory:{loader},ClassFactory:{get:()=>({use})},enumerateLoadedClassesSync:()=>['com.example.Fixture','java.lang.String'],
  enumerateClassLoaders(cb){cb.onMatch(loader);cb.onComplete();},enumerateClassLoadersSync:()=>[loader],
  vm:{getEnv:()=>({handle:ptr(0xc000)})}};
 memory.writeBigUInt64LE(0xa000n,0xc000);memory.writeBigUInt64LE(0xb000n,0xa000+215*8);
 const sandbox={
  REBRO_CONFIG:{agents:names,options:opts,limits:{max_events:2000,max_bytes:2000000,max_string:256,max_items:100,max_hooks:64,max_per_second:100,capture_strings:false,backtrace:false,...limits}},
  Process:{id:42,arch:'arm64',platform:'linux',pointerSize:8,pageSize:4096,getCurrentThreadId:()=>7,
   enumerateModules:()=>state.modules,enumerateThreads:()=>[{id:7,name:'main',state:'running'}],
   attachModuleObserver(cb){const o={cb,detached:false,detach(){this.detached=true;}};observers.push(o);state.modules.forEach(m=>cb.onAdded(m));return o;},
   attachThreadObserver(cb){const o={cb,detached:false,detach(){this.detached=true;}};observers.push(o);cb.onAdded({id:7,name:'main'});return o;},
   getModuleByName(n){const m=state.modules.find(m=>m.name===n);if(!m)throw new Error('Module absent '+n);return m;},
   findModuleByAddress(p){return state.modules.find(m=>p.n>=m.base.n && p.n<m.base.n+m.size)||null;},
   findRangeByAddress(p){return p.n>=0&&p.n<memory.length?{base:ptr(0),size:memory.length,protection:'rwx'}:null;}},
  Frida:{version:'mock-17'},Script:{runtime:'QJS'},Module:{findGlobalExportByName:n=>symbols[n]||null},
  Interceptor:{attach(address,callbacks){const h={address,callbacks,detached:false,detach(){this.detached=true;}};hooks.push(h);return h;}},
  Memory:{scan(p,n,pattern,cb){if(pattern==='invalid')throw new Error('Invalid pattern');cb.onMatch(p,1);cb.onComplete();}},
  Stalker:{follow(tid,o){followed.set(tid,o);},unfollow(tid){followed.delete(tid);},flush(){}},
  Thread:{backtrace:()=>[ptr(0x1100)]},Backtracer:{ACCURATE:1},DebugSymbol:{fromAddress:p=>({toString:()=>String(p)+' fixture'})},
  rpc:{exports:{}},send:e=>events.push(JSON.parse(JSON.stringify(e))),ptr,
  setTimeout,clearTimeout,Date,Map,Set,Uint8Array
 };
 if(java)sandbox.Java=bridge;
 const ctx=vm.createContext(sandbox);
 vm.runInContext(fs.readFileSync(path.join(root,'runtime.js'),'utf8'),ctx);
 for(const name of names)vm.runInContext(fs.readFileSync(path.join(root,'agents',name+'.js'),'utf8'),ctx);
 return Object.assign(state,{ctx,bridge,followed,run:()=>vm.runInContext('Rebro.start()',ctx),stop:()=>ctx.rpc.exports.stop(),status:()=>ctx.rpc.exports.status()});
}
const options={
 native_trace:{module:'libc.so',symbol:'test'},
 native_memory:{module:'libc.so',offset:'0x0',length:64},
 native_scan:{module:'libc.so',offset:'0x0',length:64,pattern:'00'},
 native_stalker_calls:{module:'libc.so',symbol:'test'},
 java_methods:{class_name:'com.example.Fixture'},
 java_trace:{class_name:'com.example.Fixture',method:'calculate'}
};
for(const entry of catalog)test('initialize + cleanup '+entry.id,()=>{
 const h=harness([entry.id],options);h.run();assert.equal(h.status().statuses[entry.id].status,'active',JSON.stringify(h.events));
 assert(h.events.some(e=>e.kind==='ready'));assert(!h.events.some(e=>['hook_error','observer_error'].includes(e.kind)));
 h.stop();assert(h.hooks.every(x=>x.detached));assert(h.observers.every(x=>x.detached));
 for(const klass of h.classes.values())for(const m of Object.values(klass))if(m&&m.overloads)assert(m.overloads.every(o=>o.implementation===null));
});
for(const file of fs.readdirSync(path.join(root,'profiles')).filter(n=>n.endsWith('.json')))test('profile '+file,()=>{
 const p=JSON.parse(fs.readFileSync(path.join(root,'profiles',file)));const h=harness(p.agents,p.options||{},p.limits||{});
 h.run();assert(Object.values(h.status().statuses).every(s=>s.status==='active'),JSON.stringify(h.events));
 assert(!h.events.some(e=>e.kind==='hook_error'));h.stop();
});
test('Java preserves receiver/args/return/exception and removes its own replacement token',()=>{
 const h=harness(),ov=h.bridge.use('com.example.Fixture').calculate.overloads[0];
 const c=h.ctx.Rebro.context('test');
 c.hookJava('com.example.Fixture','calculate',()=>{throw new Error('bad observer');},null,['int']);
 const receiver={fixture:1};assert.equal(ov.implementation.call(receiver,3),17);assert.equal(ov.last.receiver,receiver);assert.equal(ov.last.args[0],3);
 const err=new Error('original');ov.error=err;assert.throws(()=>ov.implementation.call(receiver,4),e=>e===err);
 assert(h.events.some(e=>e.kind==='observer_error'));h.stop();assert.equal(ov.implementation,null);
});
test('Existing Java hook is preserved and later replacement is not removed',()=>{
 const h=harness(),ov=h.bridge.use('com.example.Fixture').calculate.overloads[0],c=h.ctx.Rebro.context('test');
 ov.implementation=()=>99;const previous=ov.implementation;
 assert.throws(()=>c.hookJava('com.example.Fixture','calculate',null,null,['int']),/already has/);assert.equal(ov.implementation,previous);
 ov.implementation=null;c.hookJava('com.example.Fixture','calculate',null,null,['int']);ov.implementation=()=>88;const later=ov.implementation;
 h.stop();assert.equal(ov.implementation,later);
});
test('String redaction + no object toString invocation',()=>{
 const h=harness(),c=h.ctx.Rebro.context('test');assert.equal(c.value('secret').length,6);
 assert.equal(c.value({$className:'A',toString(){throw Error('must not run');}}).class,'A');
 h.stop();
});
test('Quotas stop and detach listeners',()=>{
 const h=harness([],{}, {max_events:2}),c=h.ctx.Rebro.context('test');c.attach(h.ptr(0x1100),{});
 c.emit('one',{});c.emit('two',{});assert(h.status().stopped);assert(h.hooks.every(x=>x.detached));assert(h.events.some(e=>e.kind==='quota'));
});
test('Missing Java + missing module observer give failed status',()=>{
 const h=harness(['java_probe'],{}, {},false);h.run();assert.equal(h.status().statuses.java_probe.status,'failed');h.stop();
 const n=harness(['native_module_watch']);delete n.ctx.Process.attachModuleObserver;n.run();assert.equal(n.status().statuses.native_module_watch.status,'failed');n.stop();
});
test('Late module gets hook, wrong module does not',()=>{
 const h=harness(['native_trace'],{native_trace:{module:'late.so',offset:'0x10'}});h.run();assert.equal(h.hooks.length,0);
 h.observers[0].cb.onAdded({name:'wrong.so'});assert.equal(h.hooks.length,0);
 h.observers[0].cb.onAdded({...h.modules[0],name:'late.so'});assert.equal(h.hooks.length,1);h.stop();
});
test('Memory read rejects range crossing + oversized scan',()=>{
 for(const [id,opts]of [['native_memory',{module:'libc.so',offset:'0x7fff',length:2}],['native_scan',{module:'libc.so',offset:'0x0',length:1048577,pattern:'00'}]]){
 const h=harness([id],{[id]:opts});h.run();assert.equal(h.status().statuses[id].status,'failed');h.stop();}
});
test('IPv4 connect parses network byte order without reading payload',()=>{
 const h=harness(['native_network']);h.run();const p=h.ptr(0x20000);h.memory.writeUInt16LE(2,p.n);h.memory.writeUInt16BE(443,p.n+2);Buffer.from([127,0,0,1]).copy(h.memory,p.n+4);
 const hook=h.hooks.find(x=>x.address.n===h.symbols.connect.n),receiver={};hook.callbacks.onEnter.call(receiver,[h.ptr(9),p,h.ptr(16)]);hook.callbacks.onLeave.call(receiver,h.ptr(0));
 const event=h.events.find(e=>e.kind==='socket_destination');assert.deepEqual(event.data.destination,{family:'IPv4',port:443,ip:'127.0.0.1'});h.stop();
});
test('RegisterNatives table uses pointers + JNI row stride',()=>{
 const h=harness(['jni_register']);h.run();const row=0x21000;
 h.memory.writeBigUInt64LE(0x22000n,row);h.memory.writeBigUInt64LE(0x22100n,row+8);h.memory.writeBigUInt64LE(0x1100n,row+16);
 h.memory.write('fixture\0',0x22000);h.memory.write('(I)V\0',0x22100);
 h.hooks[0].callbacks.onEnter([h.ptr(0xc000),h.ptr(0x123),h.ptr(row),h.ptr(1)]);
 const e=h.events.find(e=>e.kind==='jni_registration');assert.equal(e.data.name,'fixture');assert.equal(e.data.signature,'(I)V');assert.equal(e.data.module,'libc.so');h.stop();
});
test('Stalker is stopped on function exit + script cleanup',()=>{
 const h=harness(['native_stalker_calls'],options);h.run();const callbacks=h.hooks[0].callbacks,receiver={threadId:7};
 callbacks.onEnter.call(receiver);assert.equal(h.followed.size,1);callbacks.onLeave.call(receiver);assert.equal(h.followed.size,0);
 callbacks.onEnter.call(receiver);h.stop();assert.equal(h.followed.size,0);
});
test('All Java observer callbacks preserve original return and handle representative arguments',()=>{
 for(const entry of catalog.filter(e=>e.java && e.id!=='jni_register')){
  const h=harness([entry.id],options);h.run();
  const intent={getComponent:()=>({flattenToShortString:()=> 'com.fixture/.Main'}),getAction:()=> 'view',getDataString:()=> 'https://example.test/path?q=secret'};
  const request={method:()=> 'GET',url:()=>({toString:()=> 'https://example.test/path?q=secret'})};
  const receiver={$className:'com.example.Fixture',getName:()=> 'fixture',getId:()=> 7,getAlgorithm:()=> 'AES/GCM/NoPadding',
   getDeclaringClass:()=>({getName:()=> 'com.example.Fixture'}),getProtocol:()=> 'https',getHost:()=> 'example.test',getPort:()=> 443,getPath:()=> '/path'};
  const arg=t=>t==='java.lang.String'?'com.example.Fixture':t==='int'?1:t==='boolean'?false:t==='android.content.Intent'?intent:t==='okhttp3.Request'?request:t==='java.io.File'?{getPath:()=> '/fixture'}:t.startsWith('[')?[1,2,3]:{$className:t};
  for(const klass of h.classes.values())for(const method of Object.values(klass))if(method&&method.overloads)for(const ov of method.overloads)if(ov.implementation){
   assert.equal(ov.implementation.apply(receiver,ov.argumentTypes.map(t=>arg(t.className))),17,entry.id);
  }
  assert(!h.events.some(e=>e.kind==='observer_error'),entry.id+': '+JSON.stringify(h.events));h.stop();
 }
});
process.stdout.write(JSON.stringify({node_tests:passed,device_tested:false})+'\n');
