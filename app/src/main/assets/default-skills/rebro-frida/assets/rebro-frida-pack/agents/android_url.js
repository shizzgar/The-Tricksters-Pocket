/* Rebro Pack: android_url. Original MIT code; requires external Java bridge. */
Rebro.module('android_url', true, function(o,c) {
'use strict';
c.hookJava('java.net.URL','openConnection',function(){c.emit('url_connection',{protocol:c.clip(this.getProtocol()),host:c.clip(this.getHost()),port:this.getPort(),path:c.clip(this.getPath())});});
});

