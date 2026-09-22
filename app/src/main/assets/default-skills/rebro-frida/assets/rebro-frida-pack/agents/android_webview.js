/* Rebro Pack: android_webview. Original MIT code; requires external Java bridge. */
Rebro.module('android_webview', true, function(o,c) {
'use strict';
let n=0;
n+=c.tryJava('android.webkit.WebView','loadUrl',function(a){c.emit('webview_url',{url:c.url(a[0])});});
n+=c.tryJava('android.webkit.WebView','addJavascriptInterface',function(a){c.emit('webview_interface',{name:c.clip(a[1]),class:a[0]?$class(a[0]):null});});
function $class(v){return v.$className || 'unknown';}
n+=c.tryJava('android.webkit.WebView','evaluateJavascript',function(a){c.emit('webview_eval',{characters:a[0]===null?0:String(a[0]).length});});c.require(n>0,'WebView methods unavailable');
});

