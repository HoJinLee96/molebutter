const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const vm=require('node:vm');
const actions=readFileSync('molebutter-app/src/main/resources/static/js/form-actions.js','utf8');
const settle=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
function fixture(page,{post=async()=>({})}={}){
 const html=readFileSync('molebutter-app/src/main/resources/templates/'+page+'.html','utf8'),nodes=new Map(),documentEvents={},posts=[],cooldowns=[];
 for(const [,tag,attrs,id] of html.matchAll(/<([\w-]+)\b([^>]*\bid="([^"]+)"[^>]*)>/g)){
  const events={},attributes=Object.fromEntries([...attrs.matchAll(/([\w-]+)="([^"]*)"/g)].map(([,k,v])=>[k,v]));
  const element={id,tagName:tag.toUpperCase(),type:attributes.type||(tag==='button'?'submit':'text'),value:'',textContent:'',hidden:/(?:^|\s)hidden(?:\s|$)/.test(attrs),disabled:false,noValidate:/\bnovalidate\b/.test(attrs),attributes,events,
   getAttribute(k){return this.attributes[k]??null;},hasAttribute(k){return k in this.attributes;},matches(){return false;},addEventListener(k,fn){(events[k]??=[]).push(fn);},focus(){document.activeElement=this;},reportValidity(){return this.valid!==false;},reset(){for(const node of nodes.values())if(node.form===this)node.value='';},
   async fire(type){const event={target:this,currentTarget:this,preventDefault(){this.defaultPrevented=true;},stopImmediatePropagation(){this.stopped=true;}};for(const fn of events[type]||[]){await fn(event);if(event.stopped)break;}return event;},
   async click(){if(this.disabled)return;const event=await this.fire('click');if(this.type==='submit'&&this.form&&!event.defaultPrevented)await this.form.fire('submit');}};
  nodes.set(id,element);
 }
 const $=id=>nodes.get(id),formId=({'signin':'signin-form','find-id':'find-form','signup':'info-form','find-password':'reset-form','my-page':'pw-form'})[page],form=$(formId);
 const fields=page==='signin'?['email','password']:page==='find-id'?['name','phone']:page==='signup'?['name','phone','password','password-confirm']:page==='find-password'?['password','password-confirm']:['current-password','new-password','new-password-confirm'];
 for(const id of fields){$(id).form=form;$(id).parentElement=form;}
 const submitId=({'signin':'submit-btn','find-id':'find-btn','signup':'signup-btn','find-password':'reset-btn','my-page':'change-btn'})[page];$(submitId).form=form;$(submitId).parentElement=form;
 if($('code'))$('code').parentElement=$('step-code');
 const document={activeElement:null,getElementById:$,addEventListener(k,fn){documentEvents[k]=fn;},querySelectorAll(){return [form,...['step-code','bottom-links'].map($).filter(Boolean)];}};
 const context={document,getComputedStyle:()=>({display:'block',visibility:'visible'}),location:{href:''},setError(id,message){$(id).textContent=message??'';$(id).hidden=!message;},apiPost:async(url,body)=>{posts.push({url,body});return post(url,body);},
  startCooldown(button){button.disabled=true;const stop=()=>{button.disabled=false;};cooldowns.push(stop);return stop;}};
 context.window=context;vm.runInNewContext(actions+'\n'+[...html.matchAll(/<script th:inline="none">([\s\S]*?)<\/script>/g)].map(([,source])=>source).join('\n'),context);
 return {$,posts,context,cooldowns,form,submitId,key(id,extra={}){const event={key:'Enter',target:$(id),preventDefault(){this.defaultPrevented=true;},...extra};documentEvents.keydown(event);return event;},click(id){return $(id).click();}};
}
test('login and email lookup retain declared Enter submit actions with duplicate pending requests blocked',async()=>{
 for(const page of ['signin','find-id']){const pending=deferred(),f=fixture(page,{post:()=>pending.promise});f.$(page==='signin'?'email':'name').value='테스트';f.$(page==='signin'?'password':'phone').value='입력값';
  f.key(page==='signin'?'password':'phone');f.key(page==='signin'?'email':'name');await settle();assert.equal(f.posts.length,1);assert.equal(f.$(f.submitId).disabled,true);pending.reject(Error('모의 실패'));await settle();assert.equal(f.$(f.submitId).disabled,false);assert.equal(f.$('form-error').textContent,'모의 실패');
 }
});
for(const page of ['signup','find-password']){
 test(page+' email/code Enter reuses nearby actions and restores controls after failure without bypassing cooldown',async()=>{
  let pending=deferred();const f=fixture(page,{post:()=>pending.promise});f.$('email').value='synthetic@example.test';f.key('email');f.key('email');await settle();assert.equal(f.posts.length,1);assert.equal(f.$('send-btn').disabled,true);
  pending.reject(Error('발송 실패'));await settle();assert.equal(f.$('send-btn').disabled,false);assert.equal(f.$('email').value,'synthetic@example.test');
  pending=deferred();f.key('email');pending.resolve({});await settle();assert.equal(f.$('send-btn').disabled,true);assert.equal(f.$('step-code').hidden,false);f.key('email');assert.equal(f.posts.length,2);
  pending=deferred();f.$('code').value='123456';f.key('code');f.key('code');await settle();assert.equal(f.posts.length,3);assert.equal(f.$('verify-btn').disabled,true);pending.reject(Error('인증 실패'));await settle();assert.equal(f.$('verify-btn').disabled,false);assert.equal(f.$('send-btn').disabled,true);assert.equal(f.$('code').value,'123456');
  pending=deferred();f.key('code');pending.resolve({});await settle();assert.equal(f.$('verify-btn').disabled,true);assert.equal(f.$('send-btn').hidden,true);assert.equal(f.$('email').readOnly,true);assert.equal(f.form.hidden,false);assert.equal(f.$('code').value,'');
 });
 test(page+' final write requires explicit button activation; input Enter and native submission preserve entered values',async()=>{
  const pending=deferred(),f=fixture(page,{post:()=>pending.promise});f.form.hidden=false;f.$('email').value='synthetic@example.test';f.$('password').value='safe12345';f.$('password-confirm').value='safe12345';if(page==='signup'){f.$('name').value='테스트';f.$('phone').value='010-0000-0000';}
  f.key('password-confirm');await f.form.fire('submit');assert.equal(f.posts.length,0);assert.equal(f.$(f.submitId).type,'button');const write=f.click(f.submitId);await f.click(f.submitId);assert.equal(f.posts.length,1);pending.reject(Error('저장 실패'));await write;assert.equal(f.$('password').value,'safe12345');assert.equal(f.$(f.submitId).disabled,false);assert.equal(f.$('form-error').textContent,'저장 실패');
 });
}
test('my-page password change remains explicit and retains mismatch validation and retry input',async()=>{
 const pending=deferred(),f=fixture('my-page',{post:()=>pending.promise});f.$('current-password').value='old12345';f.$('new-password').value='new12345';f.$('new-password-confirm').value='different';f.key('new-password-confirm');await f.form.fire('submit');await f.click('change-btn');assert.equal(f.posts.length,0);assert.match(f.$('form-error').textContent,/일치/);
 f.$('new-password-confirm').value='new12345';const write=f.click('change-btn');await f.click('change-btn');assert.equal(f.posts.length,1);pending.reject(Error('변경 실패'));await write;assert.equal(f.$('current-password').value,'old12345');assert.equal(f.$('new-password').value,'new12345');assert.equal(f.$('change-btn').disabled,false);
});
