/* Rebro Pack: jni_register. Original MIT code; requires external Java bridge. */
Rebro.module('jni_register', true, function(o,c) {
'use strict';
const env=Java.vm.getEnv().handle, table=env.readPointer(), target=table.add(215*Process.pointerSize).readPointer();
c.require(!target.isNull(),'JNI RegisterNatives slot is null');
c.attach(target,{onEnter(args){const count=args[3].toInt32(),methods=args[2];if(count<0||count>65536||methods.isNull())return;
for(let i=0;i<Math.min(count,c.limits.max_items);i++){try{const row=methods.add(i*3*Process.pointerSize),name=c.cstring(row.readPointer()),signature=c.cstring(row.add(Process.pointerSize).readPointer()),fn=row.add(2*Process.pointerSize).readPointer(),m=Process.findModuleByAddress(fn);
c.emit('jni_registration',{class_handle:String(args[1]),name,signature,address:String(fn),module:m?m.name:null,offset:m?String(fn.sub(m.base)):null});}catch(e){c.emit('jni_read_error',{index:i,error:String(e)});break;}}}});

});

