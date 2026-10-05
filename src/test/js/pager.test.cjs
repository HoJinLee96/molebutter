const {test}=require('node:test');
const assert=require('node:assert/strict');
const vm=require('node:vm');
const {readFileSync}=require('node:fs');

function fixture(){
    let document;
    function element(tag='div'){
        return {tag,textContent:'',attrs:{},children:[],listeners:{},disabled:false,classList:{add(){}},
            replaceChildren(){this.children=[];},append(child){this.children.push(child);},
            setAttribute(name,value){this.attrs[name]=value;},addEventListener(name,fn){this.listeners[name]=fn;},
            contains(target){return this===target||this.children.some(child=>child.contains?.(target));},
            focus(){document.activeElement=this;},click(){if(!this.disabled)this.listeners.click?.();}};
    }
    const root=element(),changes=[];
    document={activeElement:null,getElementById:()=>root,querySelectorAll:()=>[],createElement:element,createTextNode:text=>({textContent:text})};
    const context=vm.createContext({document});
    vm.runInContext(readFileSync('molebutter-app/src/main/resources/static/js/app-ui.js','utf8')+';globalThis.ui=AppUI;',context);
    const all=()=>{const walk=n=>[n,...(n.children??[]).flatMap(walk)];return walk(root);};
    return {root,document,changes,all,
        render:(page,totalPages,numbered=true,label)=>context.ui.pager('pager',{page,totalPages},page=>changes.push(page),{numbered,label}),
        button:label=>all().find(n=>n.tag==='button'&&n.textContent===label),
        numbers:()=>all().filter(n=>n.tag==='button'&&/^\d+$/.test(n.textContent)).map(n=>n.textContent)};
}

test('numbered pagination supports first, previous, numeric, next and last destinations',()=>{
    const f=fixture();f.render(7,20);
    assert.deepEqual(f.numbers(),['6','7','8','9','10']);
    for(const label of ['맨 처음','이전','6','다음','맨 마지막'])f.button(label).click();
    assert.deepEqual(f.changes,[0,6,5,8,19]);
    assert.equal(f.button('8').attrs['aria-current'],'page');f.button('8').click();assert.equal(f.changes.length,5);
});

test('first and last pages disable only unavailable directions and bound visible numbers',()=>{
    const f=fixture();f.render(0,20);
    assert.deepEqual(f.numbers(),['1','2','3','4','5']);
    assert.equal(f.button('맨 처음').disabled,true);assert.equal(f.button('이전').disabled,true);
    f.button('맨 처음').click();f.button('이전').click();assert.deepEqual(f.changes,[]);
    f.render(19,20);assert.deepEqual(f.numbers(),['16','17','18','19','20']);
    assert.equal(f.button('다음').disabled,true);assert.equal(f.button('맨 마지막').disabled,true);
});

test('empty and single-page results never request an invalid page',()=>{
    const f=fixture();f.render(0,0);assert.deepEqual(f.root.children,[]);
    f.render(0,1);assert.deepEqual(f.numbers(),['1']);
    for(const label of ['맨 처음','이전','1','다음','맨 마지막'])f.button(label).click();assert.deepEqual(f.changes,[]);
});

test('keyboard focus remains in pagination after page changes and legacy pagers are unchanged',()=>{
    const f=fixture();f.render(0,3);f.button('다음').focus();f.button('다음').click();f.render(1,3);
    assert.equal(f.document.activeElement,f.button('2'));
    f.render(1,3,false);assert.equal(f.button('맨 처음'),undefined);
    f.button('이전').click();f.button('다음').click();assert.deepEqual(f.changes,[1,0,2]);
});
