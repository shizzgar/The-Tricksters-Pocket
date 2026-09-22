/* Rebro Pack: android_digest. Original MIT code; requires external Java bridge. */
Rebro.module('android_digest', true, function(o,c) {
'use strict';
let n=0;
for(const klass of ['java.security.MessageDigest','javax.crypto.Mac']){
n+=c.tryJava(klass,'getInstance',function(a){c.emit('digest_instance',{class:klass,algorithm:c.clip(a[0])});});
n+=c.tryJava(klass,klass==='javax.crypto.Mac'?'doFinal':'digest',function(a,s,t){c.emit('digest_operation',{class:klass,algorithm:c.clip(this.getAlgorithm()),signature:t,array_length:t[0]==='[B'?c.byteLength(a[0]):null});});
}c.require(n>0,'MessageDigest/Mac methods unavailable');
});

