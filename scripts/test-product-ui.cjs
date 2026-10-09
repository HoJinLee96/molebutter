// Representative product/settings flows with real templates and local mock APIs.
const {chromium}=require('playwright');
const {server,first}=require('./product-ui-preview.cjs');
const assert=require('node:assert/strict'),fs=require('node:fs');
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,channel:'chrome'});
 try {
  const base='http://127.0.0.1:'+server.address().port;
  for(const width of [1440,390]) {
   const context=await browser.newContext({viewport:{width,height:width===390?844:1080}}),page=await context.newPage(),errors=[];
   page.setDefaultTimeout(8000);page.on('pageerror',e=>errors.push(e.message));
   await context.route('https://**',route=>route.abort());
   await page.goto(base+'/products');await page.locator(`[data-detail="${first}"]`).first().waitFor();
   await page.waitForFunction(id=>document.querySelector(`[data-inventory-summary="${id}"]`)?.textContent.includes('보유 5개'),first);
   await page.locator('#product-q').fill('HIBA');await page.locator('#product-query').click();
   await page.waitForFunction(()=>document.querySelectorAll('#product-rows tr').length===1);
   // Open the shared inventory dialog without navigating away from products.
   await page.route('**/api/inventory/stock-products/*',route=>route.fulfill({json:{code:'OK',data:{productId:first,productCode:'HIBA6F311N2',brand:'헤지스',onHand:5,pending:2,remainingAmount:null,options:[{color:'블랙',size:'FREE',onHand:5,pending:2}]}}}));
   await page.route('**/api/inventory/items?*',route=>route.fulfill({json:{code:'OK',data:{items:[],page:0,totalPages:0,totalElements:0}}}));
   await page.route('**/api/inventory/movements?*',route=>route.fulfill({json:{code:'OK',data:{items:[],page:0,totalPages:0,totalElements:0}}}));
   const stockButton=page.locator(`[data-stock-product="${first}"]`).first();
   const stockBox=await stockButton.boundingBox(),xBefore=stockBox.x;
   const marketBox=await page.locator('#product-rows .product-market-cell').first().boundingBox();
   assert(marketBox.x-(stockBox.x+stockBox.width)>=16,'inventory button must be separated from supplier information');
   assert.equal(await page.locator(`[data-inventory-summary="${first}"] .has-stock`).count(),2);
   assert.equal(await page.locator(`[data-inventory-summary="${first}"] .has-stock`).first().evaluate(el=>getComputedStyle(el).fontWeight),'700');
   await page.locator(`[data-inventory-summary="${first}"]`).first().evaluate(el=>el.querySelectorAll('.has-stock').forEach(n=>n.classList.remove('has-stock')));
   assert.equal((await stockButton.boundingBox()).x,xBefore);
   await stockButton.click();await page.locator('#stock-options').getByText('블랙',{exact:true}).waitFor();
   assert.equal(new URL(page.url()).pathname,'/products');assert.equal(await page.locator('#stock-dialog').evaluate(el=>el.open),true);
   await page.locator('[data-close="stock-dialog"]').click();
   await page.locator(`[data-detail="${first}"]`).first().click();
   await page.locator('[data-clear-selection]').click();
   await page.locator('#supplier-results [data-pick="L0"]').click();
   await page.waitForFunction(()=>document.querySelector('#detail-summary').textContent.includes('69,000원'));
   if(!await page.locator('#edit-form').evaluate(e=>e.closest('details').open))await page.getByText('기본 정보 수정',{exact:true}).click();await page.locator('#edit-query').fill('저장 전 검색어');
   await page.locator('#detail-refresh').click();await page.locator('#detail-spinner').waitFor({state:'visible'});
   await page.locator('#detail-spinner').waitFor({state:'hidden'});
   assert.equal(await page.locator('#edit-query').inputValue(),'저장 전 검색어');
   // Deleting a pending purchase closes inventory dialogs without discarding the product draft.
   let purchaseDeleted=false,deleteCalls=0;
   const purchaseItem={id:'901',purchaseId:'900',productId:first,productCode:'HIBA6F311N2',brand:'헤지스',purchasedOn:'2026-10-05',revision:0,orderedQuantity:1,receivedQuantity:0,cancelledQuantity:0,onHand:0,pending:1,color:'블랙',size:'FREE',unitPrice:null};
   const purchase={id:'900',revision:0,purchasedOn:'2026-10-05',paidOn:'2026-10-05',supplierName:'검증 구매처',orderedQuantity:1,receivedQuantity:0,cancelledQuantity:0,onHand:0,pending:1,purchaseAmount:null,deleteToken:'pending-purchase-token',deleteBlockedReason:null,items:[purchaseItem]};
   await page.route('**/api/inventory/items?*',route=>route.fulfill({json:{code:'OK',data:{items:purchaseDeleted?[]:[purchaseItem],page:0,totalPages:purchaseDeleted?0:1,totalElements:purchaseDeleted?0:1}}}));
   await page.route('**/api/inventory/items/901',route=>route.fulfill({json:{code:'OK',data:purchaseItem}}));
   await page.route('**/api/inventory/purchases/900',route=>route.fulfill({json:{code:'OK',data:purchase}}));
   await page.route('**/api/inventory/summaries?*',route=>{
    const ids=(new URL(route.request().url()).searchParams.get('productIds')||'').split(',');
    return route.fulfill({json:{code:'OK',data:ids.map(productId=>({productId,onHand:productId===first?5:0,pending:productId===first?(purchaseDeleted?1:2):0}))}});
   });
   await page.route('**/api/inventory/purchases/900/delete',route=>{
    assert.equal(route.request().method(),'POST');assert.deepEqual(route.request().postDataJSON(),{revision:0,deleteToken:purchase.deleteToken});
    assert(route.request().headers()['x-operation-id']);deleteCalls++;purchaseDeleted=true;
    return route.fulfill({json:{code:'OK',data:null}});
   });
   await page.locator('#detail-summary [data-stock-product]').click();
   await page.locator('#stock-items [data-item="901"]').click();
   await page.locator('#purchase-delete').click();
   await page.locator('#purchase-delete-form [type="submit"]').click();
   await page.locator('#purchase-delete-dialog').waitFor({state:'hidden'});
   await page.waitForFunction(()=>document.querySelector('#detail-summary [data-inventory-summary]').textContent.includes('미입고 1개'));
   assert.equal(deleteCalls,1);
   assert.equal(await page.locator('[data-inventory-dialogs] dialog[open]').count(),0);
   assert.equal(await page.locator('#product-dialog').evaluate(el=>el.open),true);
   assert.equal(await page.locator('#edit-query').inputValue(),'저장 전 검색어');
   assert.equal(new URL(page.url()).pathname,'/products');
   await page.locator('[data-close="product-dialog"]').click();

   // Create and edit through the form, then confirm deletion through the UI.
   await page.locator('#product-register').click();await page.locator('#product-create').click();
   await page.locator('#create-brand').selectOption('1');await page.locator('#create-code').fill('SMOKE-'+width);
   await page.locator('#create-query').fill('등록 검색어');await page.locator('#create-form button.primary').click();
   await page.locator('#product-dialog').waitFor({state:'visible'});
   await page.waitForFunction(()=>document.querySelector('#detail-summary').textContent.includes('등록 검색어'));
   if(!await page.locator('#edit-form').evaluate(e=>e.closest('details').open))await page.getByText('기본 정보 수정',{exact:true}).click();await page.locator('#edit-query').fill('수정 검색어');
   await page.locator('#edit-form button.primary').click();
   await page.waitForFunction(()=>document.querySelector('#detail-summary').textContent.includes('수정 검색어'));
   fs.mkdirSync('target/ui-check',{recursive:true});await page.screenshot({path:`target/ui-check/product-smoke-${width}.png`});
   await page.locator('[data-close="product-dialog"]').click();
   await page.goto(base+'/products');await page.locator('#product-q').fill('SMOKE-'+width);await page.locator('#product-query').click();
   await page.waitForFunction(code=>document.querySelector('#product-rows').textContent.includes(code), 'SMOKE-'+width);
   await page.locator('[data-delete]').first().click();await page.locator('#product-delete-form button.danger').click();
   await page.locator('#product-delete-dialog').waitFor({state:'hidden'});
   const rows=(await page.request.get(base+'/api/products?q=SMOKE-'+width).then(r=>r.json())).data.items;
   assert.equal(rows.length,0);

   // Settings write and read-only role are checked once per viewport.
   await page.goto(base+'/settings?tab=brands');await page.locator('#brand-create-form button').waitFor({state:'visible'});
   await page.waitForFunction(()=>!document.querySelector('#brand-create-form button').disabled);
   await page.locator('#brand-name').fill('검증 브랜드 '+width);await page.locator('#brand-create-form button').click();
   await page.waitForFunction(name=>document.querySelector('#settings-brand-rows').textContent.includes(name),'검증 브랜드 '+width);
   await page.goto(base+'/settings?role=PRODUCT&tab=brands');await page.locator('#settings-brand-rows tr').first().waitFor();
   assert.equal(await page.locator('#brand-create-form').count(),0);
   // Marketplace stays manual and preserves opaque page tokens; no external request is made.
   let marketCalls=0,failMarket=false;
   await page.route('**/api/marketplaces/coupang/products?*',async route=>{
    marketCalls++;const u=new URL(route.request().url());
    if(failMarket){failMarket=false;return route.fulfill({status:504,json:{code:'COUPANG_TIMEOUT',message:'쿠팡 응답 시간이 초과되었습니다.'}});}
    const second=u.searchParams.has('nextToken');
    if(second)assert.equal(u.searchParams.get('nextToken'),'0007+/=');
    await route.fulfill({json:{code:'OK',data:{items:[{sellerProductId:second?'99999999999999999999':'1',sellerProductName:second?'두번째 상품':'<b>첫 상품</b>',productId:'14784194',brand:'헤지스',statusName:'승인완료',createdAt:'2026-10-05T18:00:00'}],nextToken:second?'':'0007+/=',hasNext:!second}}});
   });
   let detailCalls=0;
   await page.route('https://images.example.test/first.svg',route=>route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="240" height="240"><rect width="240" height="240" fill="#e2e8f0"/><text x="70" y="120">첫 옵션</text></svg>'}));
   await context.route('https://img1a.coupangcdn.com/image/**',route=>{return route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jLZkAAAAASUVORK5CYII=','base64')});});
   await page.route('**/api/marketplaces/coupang/products/*',route=>{detailCalls++;return route.fulfill({json:{code:'OK',data:{product:{sellerProductId:'1',sellerProductName:'관리용 상품명',productId:'14784194',brand:'헤지스',statusName:'승인완료'},displayProductName:'고객에게 노출되는 상품명',delivery:[{name:'deliveryCharge',value:'0'}],settings:[{name:'manufacture',value:'테스트 제조사'}],items:[{sellerProductItemId:'1001',vendorItemId:'3000000000',itemName:'블랙 / FREE',current:{sellerItemId:'3000000000',salePrice:99900,amountInStock:0,onSale:true},currentError:null,images:[{url:'https://images.example.test/first.svg',type:'REPRESENTATION',order:1},{url:'https://images.example.test/missing.jpg',type:'DETAIL',order:2}],contents:[{type:'HTML',detailType:'TEXT',content:'<h2>설명 미리보기</h2><img src="http://img1a.coupangcdn.com/image/description-test.svg"><script>parent.document.body.dataset.injected="yes"</script><form action="https://bad.test"><input></form>'}],notices:[],attributes:[{name:'색상',value:'블랙',exposed:'EXPOSED'}],settings:[{name:'salePrice',value:'100000'}]},{sellerProductItemId:'1002',vendorItemId:null,itemName:'화이트 / FREE',current:null,currentError:'현재 값 미확인',images:[],contents:[{type:'IMAGE',detailType:'IMAGE',content:'vendor_inventory/synthetic-description.svg'}],settings:[],notices:[]}]}}});});
   await page.goto(base+'/marketplaces?role=ADMIN');await page.locator('#marketplace-coupang-tab').click();assert.equal(marketCalls,0);
   await page.locator('#marketplace-query button[type=submit]').click();await page.locator('#marketplace-rows').getByText('<b>첫 상품</b>',{exact:true}).waitFor();
   assert.equal(await page.locator('#marketplace-rows b').count(),0);
   await page.locator('#marketplace-next').click();await page.locator('#marketplace-rows').getByText('두번째 상품',{exact:true}).waitFor();assert(await page.locator('#marketplace-next').isDisabled());
   await page.locator('#marketplace-prev').click();await page.locator('#marketplace-rows').getByText('<b>첫 상품</b>',{exact:true}).waitFor();
   await page.locator('#marketplace-size').selectOption('50');assert(await page.locator('#marketplace-next').isDisabled());
   failMarket=true;await page.locator('#marketplace-query button[type=submit]').click();await page.locator('#marketplace-error').waitFor({state:'visible'});
   await page.locator('#marketplace-query button[type=submit]').click();await page.locator('#marketplace-rows').getByText('<b>첫 상품</b>',{exact:true}).waitFor();
   await page.screenshot({path:`target/ui-check/marketplace-smoke-${width}.png`});
   await page.locator('[data-marketplace-product="1"]').click();
   await page.locator('#marketplace-detail-content').getByText('고객에게 노출되는 상품명',{exact:true}).waitFor();
   assert.equal(await page.locator('#marketplace-detail').evaluate(el=>el.open),true);
   assert.equal(await page.locator('.marketplace-tabs [role=tab]').count(),4);
   await page.locator('#marketplace-detail-content').getByText('블랙 / FREE',{exact:true}).waitFor();
   await page.locator('#marketplace-detail-content').getByText('99,900원',{exact:true}).waitFor();
   await page.locator('#marketplace-detail-content').getByText('0개',{exact:true}).waitFor();
   await page.locator('#marketplace-detail-content').getByText('판매 중',{exact:true}).waitFor();
   await page.screenshot({path:`target/ui-check/marketplace-detail-${width}.png`});
   await page.locator('[data-detail-tab=images]').click();await page.locator('.marketplace-images img').first().waitFor();
   await page.locator('.marketplace-images img').first().evaluate(img=>img.decode());
   await page.locator('.marketplace-images .marketplace-image-fallback').last().waitFor({state:'visible'});
   assert(await page.locator('.marketplace-images img').last().isHidden());
   await page.locator('#marketplace-option').selectOption('1');await page.getByText('등록된 이미지가 없습니다.',{exact:true}).waitFor();
   await page.locator('#marketplace-option').selectOption('0');
   await page.screenshot({path:`target/ui-check/marketplace-images-${width}.png`});

   assert.equal(await page.locator('iframe').getAttribute('sandbox'),'');
   await page.frameLocator('iframe').getByText('설명 미리보기',{exact:true}).waitFor();
   assert.equal(await page.locator('body').getAttribute('data-injected'),null);
   assert.equal(await page.frameLocator('iframe').locator('img').getAttribute('src'),'https://img1a.coupangcdn.com/image/description-test.svg');
   // Chrome's opaque srcdoc frame can issue its first request before Playwright attaches
   // interception. Reissue only the synthetic fixture after attachment to verify decoding.
   await page.frameLocator('iframe').locator('img').evaluate(img=>{img.src=img.src+'?fixture=1';return img.decode();});
   assert((await page.frameLocator('iframe').locator('img').getAttribute('src')).startsWith('https://'));
   await page.locator('#marketplace-option').selectOption('1');
   await page.locator('.marketplace-content-image img').evaluate(img=>img.decode());
   assert.equal(await page.locator('.marketplace-content-image img').getAttribute('src'),'https://img1a.coupangcdn.com/image/vendor_inventory/synthetic-description.svg');

   for(const tab of ['delivery','settings','basic'])await page.locator(`[data-detail-tab=${tab}]`).click();
   assert.equal(detailCalls,1);
   await page.locator('#marketplace-detail-close').click();assert.equal(await page.locator('#marketplace-detail').evaluate(el=>el.open),false);
   assert.deepEqual(errors,[]);await context.close();
   console.log(`PASS product/settings smoke: search, supplier selection, refresh/input preservation, create/edit/delete, settings permissions (${width}px)`);
  }
 }finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(err=>{console.error(err);process.exitCode=1;server.close();});
