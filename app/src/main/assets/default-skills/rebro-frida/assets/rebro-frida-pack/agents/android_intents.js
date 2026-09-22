/* Rebro Pack: android_intents. Original MIT code; requires external Java bridge. */
Rebro.module('android_intents', true, function(o,c) {
'use strict';
let n=0;for(const method of ['startActivity','startService','sendBroadcast'])n+=c.tryJava('android.content.ContextWrapper',method,function(a){const i=a[0];if(!i)return;const component=i.getComponent(),data=i.getDataString();c.emit('intent',{method,action:c.clip(i.getAction()),component:component?c.clip(component.flattenToShortString()):null,data:data?c.url(data):null});});c.require(n>0,'ContextWrapper intent methods unavailable');
});

