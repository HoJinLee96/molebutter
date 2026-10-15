const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const {environment}=require('./helpers/form-dom.cjs');
const source=readFileSync('molebutter-app/src/main/resources/static/js/inventory.js','utf8');
const html=readFileSync('molebutter-app/src/main/resources/templates/inventory.html','utf8')+readFileSync('molebutter-app/src/main/resources/templates/fragments/inventory-dialogs.html','utf8');
function fixture(){
 const f=environment(html),calls=[],posts=[];f.document.body.dataset.inventoryAdmin='true';
 const order={id:'order-1',revision:0,purchasedOn:'2026-10-09',paidOn:null,orderNumber:'original',orderUrl:'',supplierName:'store',paymentMethodId:null,paymentMethod:'',paymentAlias:'',paymentAmount:null,purchaseAmount:null,privateNote:'',orderedQuantity:3,receivedQuantity:0,pending:3,cancelledQuantity:0,items:[{id:'item-1',purchaseId:'order-1',revision:0,productCode:'SKU1',brand:'brand',color:'brown',size:'FREE',publicNote:'',orderedQuantity:3,receivedQuantity:0,pending:3,cancelledQuantity:0,onHand:0,unitPrice:null,remainingAmount:null}]};
 const empty={items:[],page:0,totalElements:0,totalPages:0};
 Object.assign(f.context,{AppUI:{escape:v=>String(v??''),uuid:()=> 'operation-1'},apiGet:async url=>{calls.push(url);if(url==='/api/inventory/purchases/order-1')return structuredClone(order);if(url.startsWith('/api/inventory/stock-totals'))return {totalOnHand:0,filteredOnHand:0};if(url.startsWith('/api/settings/'))return [];return empty;},apiPost:async(url,body)=>{posts.push({url,body});if(url==='/api/inventory/purchases/order-1')Object.assign(order,body,{revision:order.revision+1});return {};},apiRequest:async(url,options)=>{const body=JSON.parse(options.body);posts.push({url,body,options});const item=order.items[0];item.pending-=body.quantity;item.receivedQuantity+=body.quantity;item.revision++;order.pending=item.pending;return structuredClone(item);}});
 f.run(source);
 async function action(dataset){const button=f.document.createElement('button');for(const[key,value]of Object.entries(dataset))button.dataset[key]=value;f.document.body.append(button);await button.click();await f.settle();button.remove();}
 return {...f,calls,posts,order,action};
}
test('inventory filter Enter queries without writing',async()=>{
 const f=fixture();await f.settle();const before=f.calls.length;f.$('inventory-q').value='SKU1';f.key(f.$('inventory-q'));await f.settle();assert(f.calls.length>before);assert(f.calls.slice(before).some(url=>url.includes('q=SKU1')));assert.equal(f.posts.length,0);
});
test('external purchase save follows the newly rendered form and native submits cannot write',async()=>{
 const f=fixture();await f.settle();await f.action({order:'order-1'});
 for(const value of ['first edit','second edit']){
  await f.$('purchase-edit').click();await f.settle();const form=f.$('purchase-view-form'),input=form.elements.namedItem('orderNumber');input.value=value;
  f.key(input);await form.fire('submit');assert.equal(f.posts.length,value==='first edit'?0:1);assert.equal(input.value,value);
  await f.$('purchase-save').click();await f.settle();assert.equal(f.posts.at(-1).body.orderNumber,value);assert.equal(f.$('purchase-view-error').hidden,true);
 }
 assert.equal(f.posts.length,2);
});
test('dynamic purchase item edit keeps Enter local and button activation saves once',async()=>{
 const f=fixture();await f.settle();await f.action({order:'order-1'});await f.$('purchase-view-content').querySelector('[data-edit-product]').click();
 const form=f.$('purchase-view-content').querySelector('[data-purchase-item-form]'),color=form.elements.namedItem('color');color.value='edited';f.key(color);await form.fire('submit');assert.equal(f.posts.length,0);assert.equal(color.value,'edited');
 await form.querySelector('[data-save-product]').click();await f.settle();assert.equal(f.posts.length,1,JSON.stringify({button:form.querySelector('[data-save-product]').hidden,disabled:form.querySelector('[data-save-product]').disabled,dataset:form.dataset,error:f.$('purchase-item-error-item-1').textContent}));assert.equal(f.posts[0].url,'/api/inventory/items/item-1');assert.equal(f.posts[0].body.color,'edited');
});
test('delegated receipt write is replaced by explicit row actions while preserving edited quantities',async()=>{
 const f=fixture();await f.settle();await f.action({orderReceipt:'order-1'});
 const form=f.$('purchase-receipt-items').querySelector('[data-receipt-item]'),quantity=form.elements.namedItem('quantity');quantity.value='1';f.key(quantity);await form.fire('submit');assert.equal(f.posts.length,0);assert.equal(quantity.value,'1');
 await form.querySelector('[data-receipt-submit]').click();await f.settle();assert.equal(f.posts.length,1);assert.equal(f.posts[0].body.quantity,1);assert.equal(f.posts[0].body.kind,'RECEIPT');assert.equal(f.posts[0].options.headers['X-Operation-Id'],'operation-1');assert.equal(f.$('purchase-receipt-items').querySelector('[data-receipt-item]'),form);
});
