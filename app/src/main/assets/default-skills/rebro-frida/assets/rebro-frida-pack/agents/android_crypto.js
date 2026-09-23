/* Rebro Pack: android_crypto. Original MIT code; requires external Java bridge. */
Rebro.module('android_crypto', true, function(o,c) {
'use strict';
let n=0;
n+=c.tryJava('javax.crypto.Cipher','getInstance',function(a){c.emit('cipher_instance',{transformation:c.clip(a[0])});});
n+=c.tryJava('javax.crypto.Cipher','init',function(a){c.emit('cipher_init',{mode:a[0],algorithm:c.clip(this.getAlgorithm())});});
n+=c.tryJava('javax.crypto.Cipher','doFinal',function(a,s,t){c.emit('cipher_final',{algorithm:c.clip(this.getAlgorithm()),signature:t,input_array_length:t[0]==='[B'?c.byteLength(a[0]):null});});
c.require(n>0,'Cipher methods unavailable');
});

