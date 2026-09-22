/* Rebro Pack: android_files. Original MIT code; requires external Java bridge. */
Rebro.module('android_files', true, function(o,c) {
'use strict';
let n=0;for(const klass of ['java.io.FileInputStream','java.io.FileOutputStream'])n+=c.tryJava(klass,'$init',function(a,s,t){let path=null;if(t[0]==='java.lang.String')path=c.clip(a[0]);else if(t[0]==='java.io.File'&&a[0])path=c.clip(a[0].getPath());c.emit('java_file',{class:klass,path,signature:t});});c.require(n>0,'Java file constructors unavailable');
});

