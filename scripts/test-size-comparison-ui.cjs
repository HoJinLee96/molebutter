// Local editor + real server-rendered PNG fixtures. No supplier, R2 or marketplace network traffic.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '..'), resources = path.join(root, 'molebutter-app/src/main/resources/static');
const fixture = JSON.parse(fs.readFileSync(path.join(root, 'molebutter-app/target/size-comparison-check/comparison-fixtures/previews.json')));
const html = `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<link rel="stylesheet" href="/css/app.css"><link rel="stylesheet" href="/css/product-images.css">
<body><main class="product-image-workspace" style="padding:20px;min-height:1800px"><button id="open" type="button" class="btn">사이즈 편집</button></main>
<script src="/js/app-ui.js"></script><script src="/js/form-actions.js"></script><script src="/js/modal-scroll.js"></script>
<script>window.toasts=[];window.showToast=(message)=>toasts.push(message);window.saved=[];window.failGenerate=false;window.initialLayout=null;
window.apiGet=async(url)=>{let r=await fetch(url);if(!r.ok)throw Error('합성 목록 실패');return r.json()};
window.apiPost=async(url,body)=>{let r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!r.ok)throw Error('합성 미리보기 실패');return r.json()};</script>
<script type="module">import {openSizeGuideEditor} from '/js/product-images/size-guide-editor.js';window.openEditor=(layout=window.initialLayout)=>openSizeGuideEditor({
 productCode:'TEST-BAG',brandCode:'DAKS',templateKey:'bag-tote',baseImageUrl:'https://source.invalid/photo.jpg',dimensions:{width:'30',depth:'14',height:'10'},
 initialLayout:layout,onConfirm:async(result)=>{if(failGenerate)throw Error('합성 생성 실패');saved.push(structuredClone(result));window.initialLayout=structuredClone(result.layout)}});
 document.getElementById('open').onclick=()=>window.openEditor();</script></body></html>`;
const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://local');
    if (url.pathname === '/') { response.setHeader('Content-Type','text/html'); return response.end(html); }
    if (/^\/(js\/(product-images\/)?[\w.-]+|css\/[\w.-]+)$/.test(url.pathname)) {
        const file = path.join(resources,url.pathname);
        if (fs.existsSync(file)) { response.setHeader('Content-Type',file.endsWith('.js')?'application/javascript':'text/css'); return response.end(fs.readFileSync(file)); }
    }
    response.statusCode=404;response.end();
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 fs.mkdirSync(path.join(root,'target/ui-check'),{recursive:true});
 const origin='http://127.0.0.1:'+server.address().port;
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try { for (const width of [1920,1440,390]) {
  const context=await browser.newContext({viewport:{width,height:950},hasTouch:width===390,deviceScaleFactor:2});
  const page=await context.newPage(), errors=[], unexpected=[], requests=[];
  let hold=null, pendingResolve=null, failPreview=false, failCatalog=true, realZoom=false, realTransitions=false;
  page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());
   if(url.origin!==origin){unexpected.push(req.url());return route.abort()}
   if(url.pathname==='/api/product-images/size-guide/comparisons'){
    if(failCatalog)return route.fulfill({status:503,json:{}});
    return route.fulfill({json:fixture.presets});
   }
   if(url.pathname.endsWith('/size-guide/preview')){
    const body=req.postDataJSON();requests.push(body);
    if(failPreview)return route.fulfill({status:500,json:{}});
    const placements=body.layout?.comparisons ?? (body.layout?.comparison ? [body.layout.comparison] : []);
    const preset=placements[0]?.presetId||'none';
    if(realTransitions) return route.fulfill({json:fixture.transitions[preset!=='none'?'with':body.layout?'removed':'without']});
    if(realZoom && preset==='samdasoo-500') {
     const rendered=fixture.zooms[String(body.layout.photo.scale)];
     assert(rendered,'actual server PNG fixture exists for requested zoom');
     return route.fulfill({json:rendered});
    }
    const sample=structuredClone(placements.length>1 ? fixture.multi : fixture.samples[preset]);
    if(body.layout && !(placements.length===1 && placements[0].centerXRatio==null)) {
     sample.layout=structuredClone(body.layout);
     sample.layout.comparisons=placements.map((p,i)=>p.centerXRatio!=null ? structuredClone(p) :
       {...p,centerXRatio:fixture.multi.layout.comparisons[i % 3].centerXRatio,baseYRatio:fixture.multi.layout.comparisons[i % 3].baseYRatio});
     sample.layout.comparison=sample.layout.comparisons[0]||null;
    }
    if(hold){hold=false;await new Promise(resolve=>pendingResolve=resolve)}
    return route.fulfill({json:sample});
   }
   return route.continue();
  });
  await page.goto(origin);await page.waitForFunction(()=>window.openEditor);
  await page.locator('#open').click();await page.locator('[data-sg-kind="height"][data-sg-point="end"]').waitFor();
  await page.waitForFunction(()=>!document.querySelector('[data-sg-comparison-retry]').hidden);
  failCatalog=false;await page.locator('[data-sg-comparison-retry]').click();
  await page.waitForFunction(()=>!document.querySelector('[data-sg-comparison]').disabled);
  assert.equal(await page.locator('[data-sg-comparison] option').count(),9);
  for (const preset of fixture.presets) {
   if(await page.locator('[data-sg-comparison-remove]').count()) {
    await page.locator('[data-sg-comparison-clear]').click();
    await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
   }
   await page.locator('[data-sg-comparison]').selectOption(preset.id);
   await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden && !!document.querySelector('[data-sg-kind="comparison"]'));
   assert.match(await page.locator('[data-sg-comparison-details]').textContent(),new RegExp('높이 '+preset.heightCm+'cm'));
   const name=await page.locator('[data-sg-preview]').getAttribute('src');assert.equal(name,fixture.samples[preset.id].imageDataUrl);
   assert(Math.abs(Number(await page.locator('[data-sg-scale]').inputValue())-fixture.samples[preset.id].layout.photo.scale)<1e-12,'slider shows the baked final output scale');
   assert.equal(await page.locator('[data-sg-scale]').getAttribute('min'),'0.01');
   assert.equal(await page.locator('[data-sg-scale]').getAttribute('max'),'2.5');
   const point=fixture.samples[preset.id].layout.comparison,t=fixture.samples[preset.id].sceneTransform;
   const left=await page.locator('[data-sg-kind="comparison"]').evaluate(el=>parseFloat(el.style.left)/100);
   assert(Math.abs(left-(point.centerXRatio*t.scale+t.offsetXRatio))<1e-6);
  }
  // Actual wallet PNGs with large source padding: adding/removing a bottle keeps size when space permits.
  await page.locator('[data-sg-cancel]').click();realTransitions=true;
  await page.locator('#open').click();await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  const walletPixels=[];
  for(const choice of ['', 'samdasoo-500', '']) {
   if(choice!=='' || walletPixels.length) {
    await page.locator('[data-sg-comparison]').selectOption(choice);
    await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
   }
   assert.equal(Number(await page.locator('[data-sg-scale]').inputValue()),2);
   assert.equal(await page.locator('[data-sg-scale]').getAttribute('min'),'0.01');
   assert.equal(await page.locator('[data-sg-scale]').getAttribute('max'),'2.5');
   walletPixels.push(await page.locator('[data-sg-preview]').evaluate(async img=>{
    await img.decode();const canvas=document.createElement('canvas');canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;
    const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);const pixels=ctx.getImageData(0,0,canvas.width,Math.round(canvas.height*300/509)).data;
    let count=0;for(let i=0;i<pixels.length;i+=4)if(pixels[i]>90&&pixels[i]<210&&pixels[i+1]<pixels[i]-5&&pixels[i+2]<pixels[i+1]-5)count++;
    return count;
   }));
   if(choice)await page.screenshot({path:path.join(root,`target/ui-check/size-output-wallet-${width}-with.png`)});
  }
  assert(Math.abs(walletPixels[0]-walletPixels[1])<30 && Math.abs(walletPixels[1]-walletPixels[2])<30,'wallet output size survives comparison toggles');
  await page.locator('[data-sg-cancel]').click();realTransitions=false;
  await page.locator('#open').click();await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  await page.locator('[data-sg-comparison]').selectOption('samdasoo-500');
  await page.waitForFunction(()=>document.querySelector('[data-sg-comparison-details]').textContent.includes('21cm') && document.querySelector('[data-sg-spinner]').hidden);
  // Inspect actual server PNG pixels: a layout-only mock missed the original inverse-fit cancellation.
  realZoom=true;
  const zoomPixels=[];
  for(const scale of [.2,.4]) {
   await page.locator('[data-sg-scale]').evaluate((el,value)=>{el.value=String(value);el.dispatchEvent(new Event('input',{bubbles:true}))},scale);
   await page.waitForTimeout(350);await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
   assert.equal(await page.locator('[data-sg-preview]').getAttribute('src'),fixture.zooms[String(scale)].imageDataUrl);
   zoomPixels.push(await page.locator('[data-sg-preview]').evaluate(async img=>{
    await img.decode();const canvas=document.createElement('canvas');canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;
    const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);const pixels=ctx.getImageData(0,0,canvas.width,Math.round(canvas.height*300/509)).data;
    let count=0;for(let i=0;i<pixels.length;i+=4)if(pixels[i]>90&&pixels[i]<210&&pixels[i+1]<pixels[i]-5&&pixels[i+2]<pixels[i+1]-5)count++;
    return count;
   }));
  }
  assert(zoomPixels[1]>zoomPixels[0]*3,'enlarging the slider visibly increases actual product pixels');
  const maximum=Number(await page.locator('[data-sg-scale]').getAttribute('max'));
  assert.equal(maximum,2.5,'slider range stays the same with and without comparisons');
  assert.match(await page.locator('[data-sg-scale-maximum]').textContent(),/현재 배치 최대/);
  realZoom=false;await page.locator('[data-sg-cancel]').click();await page.locator('#open').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  await page.locator('[data-sg-comparison]').selectOption('samdasoo-500');
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden && !!document.querySelector('[data-sg-kind="comparison"]'));
  const stage=await page.locator('[data-sg-overlay]').boundingBox();
  const end=await page.locator('[data-sg-kind="height"][data-sg-point="end"]').boundingBox();
  await page.mouse.move(end.x+end.width/2,end.y+end.height/2);await page.mouse.down();
  await page.keyboard.down('Alt');await page.mouse.move(stage.x+stage.width*.8,stage.y+stage.height*.43,{steps:5});await page.keyboard.up('Alt');await page.mouse.up();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  const edited=requests.at(-1).layout;
  assert.equal(edited.height.startXRatio,edited.height.endXRatio,'height stays vertical even with Alt');
  const comparisonHandle=await page.locator('[data-sg-kind="comparison"]').boundingBox();
  const touch={pointerId:77,pointerType:'touch',bubbles:true,clientX:comparisonHandle.x+comparisonHandle.width/2,clientY:comparisonHandle.y+comparisonHandle.height/2};
  await page.locator('[data-sg-kind="comparison"]').dispatchEvent('pointerdown',touch);
  await page.locator('[data-sg-overlay]').dispatchEvent('pointermove',{...touch,clientX:touch.clientX-6});
  await page.locator('[data-sg-overlay]').dispatchEvent('pointerup',touch);
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  await page.locator('[data-sg-kind="comparison"]').focus();await page.keyboard.press('ArrowLeft');
  await page.waitForTimeout(350);await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  assert(requests.at(-1).layout.comparison.centerXRatio<edited.comparison.centerXRatio);
  const before=requests.at(-1).layout;
  await page.locator('[data-sg-scale]').evaluate(el=>{el.value='1.1';el.dispatchEvent(new Event('input',{bubbles:true}))});
  await page.waitForTimeout(350);await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  const after=requests.at(-1).layout;
  const factor=after.photo.scale/before.photo.scale;
  assert(Math.abs((after.height.endYRatio-after.height.startYRatio)/(before.height.endYRatio-before.height.startYRatio)-factor)<1e-8);
  // Failed preview leaves selected model and local editing state available.
  failPreview=true;await page.locator('[data-sg-comparison]').selectOption('iphone-16');
  await page.waitForFunction(()=>document.querySelector('[data-sg-comparison-status]').textContent.includes('실패'));
  assert.equal(await page.locator('[data-sg-comparison-remove]').count(),2);
  assert.equal(await page.locator('[data-sg-comparison] option[value="iphone-16"]').evaluate(el=>el.disabled),true);
  failPreview=false;await page.locator('[data-sg-comparison-remove="1"]').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  await page.evaluate(()=>document.querySelectorAll('.toast').forEach(el=>el.remove()));
  await page.screenshot({path:path.join(root,`target/ui-check/size-comparison-${width}.png`)});
  // Several actual rendered comparison photos, each with its own touch/keyboard handle and remove action.
  for(const id of ['iphone-16','galaxy-s26']) {
   await page.locator('[data-sg-comparison]').selectOption(id);
   await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  }
  assert.equal(await page.locator('[data-sg-comparison-remove]').count(),3);
  assert.equal(await page.locator('[data-sg-preview]').getAttribute('src'),fixture.multi.imageDataUrl);
  await page.locator('[data-sg-preview]').evaluate(async img=>{await img.decode();if(img.naturalWidth!==2880||img.naturalHeight!==1880)throw Error('High resolution PNG lost')});
  assert.equal(requests.at(-1).layout.comparisons.length,3);
  const unmoved=structuredClone(requests.at(-1).layout.comparisons[0]);
  const secondX=requests.at(-1).layout.comparisons[1].centerXRatio;
  await page.locator('[data-sg-kind="comparison:1"]').focus();await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(350);await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  assert(requests.at(-1).layout.comparisons[1].centerXRatio>secondX);
  assert.deepEqual(requests.at(-1).layout.comparisons[0],unmoved,'moving another object preserves the first');
  await page.screenshot({path:path.join(root,`target/ui-check/size-comparison-multi-${width}.png`)});
  await page.locator('[data-sg-comparison-remove="2"]').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  await page.locator('[data-sg-comparison-remove="1"]').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  const dims=await page.evaluate(()=>({w:innerWidth,scroll:document.documentElement.scrollWidth}));assert(dims.scroll<=dims.w+1);
  assert(await page.evaluate(()=>document.documentElement.classList.contains('modal-scroll-locked')));
  await page.locator('[data-sg-form]').evaluate(form=>form.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
  assert.equal(await page.evaluate(()=>saved.length),0);
  await page.evaluate(()=>{failGenerate=true});await page.locator('[data-sg-confirm]').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-confirm]').textContent==='확인');assert(await page.locator('#sizeGuideEditorModal').evaluate(el=>el.open));
  await page.evaluate(()=>{failGenerate=false});await page.locator('[data-sg-confirm]').focus();await page.keyboard.press('Enter');
  await page.waitForFunction(()=>saved.length===1);assert.equal(await page.locator('#sizeGuideEditorModal').evaluate(el=>el.open),false);
  await page.locator('#open').click();await page.waitForFunction(()=>document.querySelector('[data-sg-comparison-list]').textContent.includes('삼다수') && document.querySelector('[data-sg-spinner]').hidden);
  assert.deepEqual(requests.at(-1).layout,await page.evaluate(()=>initialLayout));
  await page.locator('[data-sg-comparison]').selectOption('');await page.waitForFunction(()=>!document.querySelector('[data-sg-kind="comparison"]') && document.querySelector('[data-sg-spinner]').hidden);
  assert.equal(requests.at(-1).layout.comparison,null);
  // Responses from a previous edit or closed dialog cannot replace the new choice.
  hold=true;await page.locator('[data-sg-comparison]').selectOption('iphone-16');
  await page.waitForFunction(()=>!document.querySelector('[data-sg-spinner]').hidden);
  for(let i=0;!pendingResolve&&i<50;i++)await page.waitForTimeout(10);assert(pendingResolve);
  await page.locator('[data-sg-comparison]').selectOption('galaxy-s26');await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  pendingResolve();await page.waitForTimeout(50);assert.equal(await page.locator('[data-sg-comparison-remove]').count(),2);
  assert.match(await page.locator('[data-sg-comparison-list]').textContent(),/Galaxy S26/);
  hold=true;pendingResolve=null;await page.locator('[data-sg-comparison]').selectOption('ipad-air-11-m4');
  for(let i=0;!pendingResolve&&i<50;i++)await page.waitForTimeout(10);assert(pendingResolve);
  await page.locator('[data-sg-cancel]').click();await page.locator('#open').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-comparison-list]').textContent.includes('삼다수') && document.querySelector('[data-sg-spinner]').hidden);
  pendingResolve();await page.waitForTimeout(50);assert.equal(await page.locator('[data-sg-comparison-remove]').count(),1);
  assert.match(await page.locator('[data-sg-comparison-list]').textContent(),/삼다수/);
  await page.locator('[data-sg-reset]').click();await page.waitForFunction(()=>document.querySelector('[data-sg-comparison]').value==='' && document.querySelector('[data-sg-spinner]').hidden);
  await page.locator('[data-sg-confirm]').focus();await page.keyboard.press('Space');await page.waitForFunction(()=>saved.length===2);
  // Save and reopen a multiple-object snapshot, then remove its first object without changing the survivors.
  await page.locator('#open').click();
  await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  for(const id of ['samdasoo-500','iphone-16','galaxy-s26']) {
   await page.locator('[data-sg-comparison]').selectOption(id);
   await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  }
  await page.locator('[data-sg-confirm]').click();await page.waitForFunction(()=>saved.length===3);
  const snapshot=await page.evaluate(()=>structuredClone(initialLayout));
  assert.equal(snapshot.comparisons.length,3);
  await page.locator('#open').click();await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  assert.deepEqual(requests.at(-1).layout,snapshot);
  assert.equal(await page.locator('[data-sg-comparison-remove]').count(),3);
  await page.locator('[data-sg-comparison-remove="0"]').click();await page.waitForFunction(()=>document.querySelector('[data-sg-spinner]').hidden);
  assert.deepEqual(requests.at(-1).layout.comparisons,snapshot.comparisons.slice(1));
  assert.deepEqual(requests.at(-1).layout.comparison,snapshot.comparisons[1]);
  await page.locator('[data-sg-cancel]').click();
  assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
  console.log(`PASS size comparison ${width}px: 2880x1880 PNGs at DPR2, three objects with independent movement/removal, 8 photo PNG fixtures with internal model names, final output scale/fixed slider range, wallet toggle pixel equality, actual zoom growth, visible soft limit, catalog retry, drag, vertical height, keyboard, failure preservation, FormActions, restore/none/reset, stale responses, scroll lock`);
  await context.close();
 }} finally {await browser.close();await new Promise(resolve=>server.close(resolve))}
})().catch(error=>{console.error(error);process.exitCode=1;server.close()});
