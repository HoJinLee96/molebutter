const {chromium}=require('playwright'),assert=require('node:assert/strict');
const {server}=require('./product-ui-preview.cjs');
(async()=>{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({channel:'chrome',headless:true});
 try{for(const width of [1920,390]){
  const page=await browser.newPage({viewport:{width,height:950}}),errors=[];page.on('pageerror',e=>errors.push(e.message));let posts=[],job=null,fail=true;
  await page.route('**/api/coupang/**',async r=>{let path=new URL(r.request().url()).pathname;const reply=data=>r.fulfill({json:{code:'OK',data}});
   if(path.endsWith('/brands'))return reply([]);
   if(path.includes('/collections')){
    if(r.request().method()==='POST'){
     posts.push(r.request().postDataJSON());if(fail)return r.fulfill({status:503,json:{code:'UNAVAILABLE',message:'모의 요청 실패'}});
     const body=posts.at(-1);job={id:'71',mode:'RECENT',status:'RUNNING',phase:'LIST',listComplete:false,discovered:0,completed:0,failed:0,recent:{...body,currentDate:body.createdDateTo,apiPages:3,candidates:140,selected:0,selectionComplete:false}};
    }return reply(job);
   }
   if(path==='/api/coupang/products')return reply({items:[],page:0,totalPages:0,totalElements:0,productCount:0,counts:{all:0,selling:0,soldOut:0,stopped:0,deleted:0}});
   return reply(null);
  });
  await page.goto('http://127.0.0.1:'+server.address().port+'/coupang/products');await page.locator('#coupang-collect-recent:not([disabled])').waitFor();
  await page.locator('#coupang-collect-recent').click();await page.locator('#coupang-recent-dialog[open]').waitFor();assert.equal(await page.locator('#coupang-recent-count').inputValue(),'100');
  await page.locator('#coupang-recent-count').fill('37');await page.locator('#coupang-recent-count').press('Enter');await page.waitForTimeout(100);assert.equal(posts.length,0);
  await page.locator('#coupang-recent-start').click();await page.locator('#coupang-recent-error').filter({hasText:'모의 요청 실패'}).waitFor();assert.equal(await page.locator('#coupang-recent-count').inputValue(),'37');assert(await page.locator('#coupang-recent-dialog').isVisible());
  fail=false;await page.locator('#coupang-recent-start').click();await page.waitForFunction(()=>!document.querySelector('#coupang-recent-dialog').open);assert.equal(posts.length,2);assert.equal(posts[1].maxProducts,37);assert.equal(posts[1].mode,'RECENT');assert.equal((Date.parse(posts[1].createdDateTo)-Date.parse(posts[1].createdDateFrom))/86400000,6);
  assert((await page.locator('#coupang-collection-summary').innerText()).includes('발견 후보 140개'));assert.equal(await page.locator('#coupang-collection-progress').getAttribute('value'),null);
  job={...job,phase:'DETAIL',discovered:37,completed:5,failed:2,recent:{...job.recent,selected:37,selectionComplete:true}};await page.waitForFunction(()=>document.querySelector('#coupang-collection-summary').textContent.includes('성공 5개'));assert.equal(await page.locator('#coupang-collection-progress').getAttribute('value'),'18');
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);await page.screenshot({path:'/tmp/coupang-recent-'+width+'.png',fullPage:true});console.log('PASS recent collection PC/mobile',width);await page.close();
 }}finally{await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;server.close();});
