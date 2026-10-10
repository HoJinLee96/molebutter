const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const {readFileSync}=require('node:fs');
const source=readFileSync('molebutter-app/src/main/resources/static/js/form-actions.js','utf8');
function fixture(){
 const listeners={};
 const document={addEventListener(type,fn){listeners[type]=fn;}};
 const window={getComputedStyle:node=>({display:node.display||'block',visibility:node.visibility||'visible'})};
 vm.runInNewContext(source,{window,document,WeakMap,WeakSet,Set});
 function node(tagName='INPUT',extra={}){
  const events={};
  const element={tagName,type:'text',isConnected:true,disabled:false,parentElement:null,attributes:{},events,clicks:0,
   matches(){return this.disabledByFieldset||false;},getAttribute(k){return this.attributes[k]??null;},hasAttribute(k){return k in this.attributes;},
   addEventListener(type,fn){(events[type]??=[]).push(fn);},
   async fire(type,extra={}){const ev={target:this,currentTarget:this,preventDefault(){this.defaultPrevented=true;},stopImmediatePropagation(){this.stopped=true;},...extra};for(const fn of events[type]||[]){await fn(ev);if(ev.stopped)break;}return ev;},
   click(){this.clicks++;return this.fire('click');},reportValidity(){this.validations=(this.validations||0)+1;return this.valid!==false;},...extra};
  return element;
 }
 function key(input,extra={}){const event={key:'Enter',target:input,preventDefault(){this.defaultPrevented=true;},...extra};listeners.keydown(event);return event;}
 return {api:window.FormActions,listeners,node,key};
}
test('input Enter clicks only its declared nearby action, without implicit submit or focus movement',()=>{
 const f=fixture(),input=f.node(),button=f.node('BUTTON');
 assert.equal(f.key(input).defaultPrevented,true);assert.equal(button.clicks,0);
 f.api.bindEnter(input,button);f.key(input);assert.equal(button.clicks,1);
 f.key(input,{repeat:true});assert.equal(button.clicks,1);
 const replacement=f.node('BUTTON');f.api.bindEnter(input,replacement);f.key(input);assert.equal(button.clicks,1);assert.equal(replacement.clicks,1);
});
test('registered search/login default is used, local action overrides it, unregistered form never saves',()=>{
 const f=fixture(),form=f.node('FORM'),input=f.node('INPUT',{form}),search=f.node('BUTTON'),local=f.node('BUTTON');
 f.api.bindEnterSubmit(form,search);f.key(input);assert.equal(search.clicks,1);
 f.api.bindEnter(input,local);f.key(input);assert.equal(local.clicks,1);assert.equal(search.clicks,1);
 const other=f.node('INPUT',{form:f.node('FORM')});assert.equal(f.key(other).defaultPrevented,true);
});
test('disabled, hidden, detached, busy and closed-dialog actions cannot be invoked',()=>{
 const f=fixture(),input=f.node(),button=f.node('BUTTON');f.api.bindEnter(input,button);
 for(const extra of [{disabled:true},{disabledByFieldset:true},{hidden:true},{inert:true},{isConnected:false},{display:'none'},{visibility:'hidden'},{attributes:{'aria-busy':'true'}},{parentElement:f.node('DIALOG',{open:false})}]){
  const target=f.node('BUTTON',extra);f.api.bindEnter(input,target);f.key(input);assert.equal(target.clicks,0);
 }
 f.api.bindEnter(input,button);f.key(input);assert.equal(button.clicks,1);
});
test('IME confirmation, textarea newlines, native select/date/time/datalist and other keys are preserved',()=>{
 const f=fixture();
 for(const input of [f.node('TEXTAREA'),f.node('SELECT'),f.node('INPUT',{type:'date'}),f.node('INPUT',{type:'time'}),f.node('INPUT',{attributes:{list:'retailers'}})])assert.equal(f.key(input).defaultPrevented,undefined);
 const input=f.node(),button=f.node('BUTTON');f.api.bindEnter(input,button);
 assert.equal(f.key(input,{isComposing:true}).defaultPrevented,undefined);
 assert.equal(f.key(input,{keyCode:229}).defaultPrevented,undefined);
 f.listeners.compositionstart({target:input});f.key(input);assert.equal(button.clicks,0);
 f.listeners.compositionend({target:input});f.key(input);assert.equal(button.clicks,1);
 for(const key of ['Tab','Escape',' '])assert.equal(f.key(input,{key}).defaultPrevented,undefined);
});
test('writes require button activation, preserve submitter and external form buttons, and prevent duplicate pending writes',async()=>{
 const f=fixture(),form=f.node('FORM'),button=f.node('BUTTON',{form,parentElement:f.node('DIV')}),input=f.node('INPUT',{form});
 let calls=0,release;const pending=new Promise(resolve=>release=resolve);
 f.api.bindExplicitSubmit(form,button,async event=>{assert.equal(event.currentTarget,form);assert.equal(event.submitter,button);calls++;await pending;});
 assert.equal(button.type,'button');f.key(input);assert.equal(calls,0);
 const native=await form.fire('submit');assert.equal(native.defaultPrevented,true);assert.equal(native.stopped,true);assert.equal(calls,0);
 const clicked=button.click();await button.click();assert.equal(calls,1);release();await clicked;
 await button.click();assert.equal(calls,2);
});
test('explicit writes retain constraint validation, novalidate and binding replacement',async()=>{
 const f=fixture(),form=f.node('FORM',{valid:false}),button=f.node('BUTTON');let calls=0;
 f.api.bindExplicitSubmit(form,button,()=>calls++);await button.click();assert.equal(calls,0);assert.equal(form.validations,1);
 form.noValidate=true;await button.click();assert.equal(calls,1);
 f.api.bindExplicitSubmit(form,button,()=>calls+=10);await button.click();assert.equal(calls,11);assert.equal(button.events.click.length,1);
 form.noValidate=false;button.formNoValidate=true;await button.click();assert.equal(calls,21);
});

test('explicit readonly brand input can open search; unbound readonly and modified Enter do not use form defaults',()=>{
 const f=fixture(),form=f.node('FORM'),input=f.node('INPUT',{readOnly:true,form}),button=f.node('BUTTON');
 f.api.bindEnterSubmit(form,button);f.key(input);assert.equal(button.clicks,0);
 f.api.bindEnter(input,button);f.key(input);assert.equal(button.clicks,1);
 for(const key of ['ctrlKey','altKey','metaKey','shiftKey']){assert.equal(f.key(input,{[key]:true}).defaultPrevented,true);assert.equal(button.clicks,1);}
});
