// Small event/element fixture for form controller unit tests; browser layout is tested separately.
const vm=require('node:vm');
const {readFileSync}=require('node:fs');
function environment(html='') {
 const voids=new Set(['INPUT','IMG','BR','HR','META','LINK','SOURCE','WBR','AREA','BASE','COL','EMBED','PARAM','TRACK']);
 const camel=value=>value.replace(/-([a-z])/g,(_,ch)=>ch.toUpperCase());
 class Element {
  constructor(tag='div',attrs={}) {this.tagName=tag.toUpperCase();this.attrs={};this.dataset=new Proxy({},{set:(target,key,value)=>{target[key]=String(value);this.attrs['data-'+String(key).replace(/[A-Z]/g,ch=>'-'+ch.toLowerCase())]=String(value);return true;}});this.children=[];this.events={};this.value='';this.disabled=false;this.hidden=false;this.readOnly=false;this.open=false;this.className='';this._text='';this.type=this.tagName==='BUTTON'?'submit':this.tagName==='INPUT'?'text':'';this.classList={toggle:(name,on)=>{const names=new Set(this.className.split(/\s+/).filter(Boolean));if(on===undefined)on=!names.has(name);on?names.add(name):names.delete(name);this.className=[...names].join(' ');},contains:name=>this.className.split(/\s+/).includes(name),add:name=>this.classList.toggle(name,true),remove:name=>this.classList.toggle(name,false)};for(const [key,value]of Object.entries(attrs))this.setAttribute(key,value);}
  setAttribute(key,value){value=String(value);this.attrs[key]=value;if(key.startsWith('data-'))this.dataset[camel(key.slice(5))]=value;if(key==='class')this.className=value;if(['id','name','type','value'].includes(key))this[key]=value;if(['disabled','hidden','readonly','checked','required','novalidate'].includes(key))this[{readonly:'readOnly',novalidate:'noValidate'}[key]||key]=true;}
  getAttribute(key){return this.attrs[key]??null;}hasAttribute(key){return key in this.attrs;}removeAttribute(key){delete this.attrs[key];}
  get value(){return this._value??'';}set value(value){this._value=String(value??'');}
  get isConnected(){for(let n=this;n;n=n.parentElement)if(n===body)return true;return false;}
  get form(){if(this.attrs.form)return document.getElementById(this.attrs.form);for(let n=this.parentElement;n;n=n.parentElement)if(n.tagName==='FORM')return n;return null;}
  get elements(){return {namedItem:name=>this.querySelector(`[name="${name}"]`) };}
  get options(){return this.children.filter(n=>n.tagName==='OPTION');}add(option){this.append(option);}
  matches(selector){return selector.split(',').some(part=>{part=part.trim();if(part===':disabled')return this.disabled||!!this.closest('fieldset[disabled]')?.disabled;const last=part.split(/\s+/).at(-1);const tag=/^[a-z]+/i.exec(last)?.[0];if(tag&&this.tagName!==tag.toUpperCase())return false;const id=/#([\w-]+)/.exec(last)?.[1];if(id&&this.id!==id)return false;for(const[,name]of last.matchAll(/\.([\w-]+)/g))if(!this.classList.contains(name))return false;for(const[,name,value]of last.matchAll(/\[([^=\]]+)(?:=["']?([^"'\]]*)["']?)?\]/g)){if(!this.hasAttribute(name))return false;if(value!==undefined&&this.getAttribute(name)!==value)return false;}return true;});}
  closest(selector){for(let n=this;n;n=n.parentElement)if(n.matches(selector))return n;return null;}
  querySelectorAll(selector){const all=[];for(const child of this.children){if(child.matches(selector))all.push(child);all.push(...child.querySelectorAll(selector));}return all;}
  querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
  append(...items){for(let item of items){if(typeof item==='string'){this._text+=item;continue;}item.remove();item.parentElement=this;this.children.push(item);}}
  prepend(...items){for(const item of items.reverse()){item.remove();item.parentElement=this;this.children.unshift(item);}}
  remove(){if(this.parentElement){const siblings=this.parentElement.children;const i=siblings.indexOf(this);if(i>=0)siblings.splice(i,1);}this.parentElement=null;}
  replaceChildren(...items){for(const child of this.children)child.parentElement=null;this.children=[];this._text='';this.append(...items);}
  get textContent(){return this._text+this.children.map(child=>child.textContent).join('');}set textContent(value){this.replaceChildren();this._text=String(value??'');}
  get innerHTML(){return this._html||'';}set innerHTML(html){this.replaceChildren();this._html=html;parse(html,this);}
  addEventListener(type,fn,capture=false){(this.events[type]??=[]).push({fn,capture:capture===true});}
  async fire(type,extra={}){const event={target:this,currentTarget:this,preventDefault(){this.defaultPrevented=true;},stopImmediatePropagation(){this.stopped=true;},stopPropagation(){this.propagationStopped=true;},...extra};const chain=[];for(let n=this;n;n=n.parentElement)chain.push(n);chain.push(document);const pending=[];const invoke=(target,capture)=>{event.currentTarget=target;for(const entry of target.events[type]||[])if(entry.capture===capture){const value=entry.fn(event);if(value?.then)pending.push(value);if(event.stopped)break;}if(!capture&&!event.stopped){const value=target['on'+type]?.(event);if(value?.then)pending.push(value);}};for(const target of [...chain].reverse()){invoke(target,true);if(event.stopped||event.propagationStopped)break;}if(!event.stopped&&!event.propagationStopped)for(const target of chain){invoke(target,false);if(event.stopped||event.propagationStopped)break;}await Promise.all(pending);return event;}
  async click(){const event=await this.fire('click');if(this.type==='submit'&&!event.defaultPrevented&&this.form)await this.form.fire('submit',{submitter:this});return event;}
  reportValidity(){return this.valid!==false;}reset(){for(const input of this.querySelectorAll('input,select,textarea'))input.value=input.getAttribute('value')||'';}focus(){document.activeElement=this;}select(){}scrollIntoView(){}showModal(){this.open=true;}close(){this.open=false;return this.fire('close');}getBoundingClientRect(){return {left:0,top:0,right:100,bottom:100};}
 }
 function parse(html,parent){const stack=[parent];for(const token of String(html).match(/<[^>]+>|[^<]+/g)||[]){if(token.startsWith('</')){const tag=/^<\/([\w-]+)/.exec(token)?.[1]?.toUpperCase();const at=stack.findLastIndex(node=>node.tagName===tag);if(at>0)stack.length=at;continue;}if(token.startsWith('<!'))continue;if(token.startsWith('<')){const [,tag,rest]=/^<([\w-]+)([\s\S]*?)\/?\s*>$/.exec(token)||[];if(!tag)continue;if(tag.toUpperCase()==='OPTION'&&stack.at(-1).tagName==='OPTION')stack.pop();const attrs={};for(const[,key,double,single,bare]of rest.matchAll(/([^\s=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g))attrs[key]=double??single??bare??'';const node=new Element(tag,attrs);stack.at(-1).append(node);if(!voids.has(node.tagName)&&!token.endsWith('/>'))stack.push(node);}else stack.at(-1)._text+=token;}}
 const body=new Element('body');
 const document={body,events:{},hidden:false,activeElement:null,createElement:tag=>new Element(tag),getElementById:id=>body.querySelector('#'+id),querySelector:selector=>body.querySelector(selector),querySelectorAll:selector=>body.querySelectorAll(selector),addEventListener(type,fn,capture=false){(this.events[type]??=[]).push({fn,capture:capture===true});},dispatchEvent(){}};
 parse(html,body);
 const window={getComputedStyle:()=>({display:'block',visibility:'visible'}),addEventListener(){},setTimeout};
 const context=vm.createContext({window,document,console,URL,URLSearchParams,WeakMap,WeakSet,Set,Map,BigInt,structuredClone,setTimeout,clearTimeout,queueMicrotask,Option:class extends Element{constructor(text,value){super('option',{value});this.textContent=text;}},CustomEvent:class {constructor(type){this.type=type;}},location:{search:''},history:{replaceState(){}}});
 vm.runInContext(readFileSync('molebutter-app/src/main/resources/static/js/form-actions.js','utf8'),context);context.FormActions=window.FormActions;
 const $=id=>document.getElementById(id);
 const key=(input,extra={})=>{const event={key:'Enter',target:input,preventDefault(){this.defaultPrevented=true;},...extra};for(const{fn}of document.events.keydown||[])fn(event);return event;};
 const settle=async()=>{for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));};
 return {context,document,window,$,key,settle,run:source=>vm.runInContext(source,context)};
}
module.exports={environment};
