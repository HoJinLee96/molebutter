(() => {
'use strict';
const esc=value=>AppUI.escape(value);
let active=false;
async function open({url,assetId=null,square=false}){
 if(active)return null;active=true;let dialog;
 try{
  const source=await apiGet('/api/coupang/image-edits/source?'+new URLSearchParams(assetId?{assetId}:{url}));
  dialog=document.createElement('dialog');dialog.className='image-crop-dialog';dialog.setAttribute('aria-labelledby','image-crop-title');
  const dim=Math.min(source.width,source.height);
  let rect=source.rect||{x:0,y:0,width:square?dim:source.width,height:square?dim:source.height},locked=square,working=false,drag=null;
  if(square)rect={...rect,width:Math.min(rect.width,rect.height),height:Math.min(rect.width,rect.height)};
  dialog.innerHTML=`<div class="common-modal-heading"><h2 id="image-crop-title">이미지 편집</h2><button class="btn" type="button" data-crop-close>닫기</button></div><p>자를 범위를 선택하세요. 원본 ${source.width}×${source.height}px · 자동 크기 변경 없음</p><label><input type="checkbox" data-crop-square ${square?'checked':''}> 1:1 정사각형</label><div class="image-crop-stage"><canvas tabindex="0" aria-label="자를 범위. 드래그로 선택하고 방향키로 이동" width="${Math.min(900,source.width)}" height="${Math.round(Math.min(900,source.width)*source.height/source.width)}"></canvas></div><div class="image-crop-fields">${[['x','왼쪽'],['y','위쪽'],['width','너비'],['height','높이']].map(([key,label])=>`<label>${label}<input type="number" min="${key==='x'||key==='y'?0:1}" step="1" data-crop-field="${key}" value="${rect[key]}"></label>`).join('')}</div><p data-crop-size role="status"></p><p data-crop-error class="form-error" role="alert" hidden></p><div class="image-crop-actions"><button type="button" class="btn" data-crop-restore>원본 복원</button><button type="button" class="btn" data-crop-cancel>취소</button><button type="button" class="btn primary" data-crop-confirm>확인</button></div>`;
  document.body.append(dialog);dialog.showModal();const canvas=dialog.querySelector('canvas'),ctx=canvas.getContext('2d'),picture=new Image();
  picture.src=source.previewUrl;await picture.decode();const scale=canvas.width/source.width;
  const error=message=>{const el=dialog.querySelector('[data-crop-error]');el.textContent=message;el.hidden=!message;};
  function normalize(key=null){for(const k of Object.keys(rect))rect[k]=Number.isFinite(rect[k])?Math.round(rect[k]):0;rect.x=Math.max(0,Math.min(source.width-1,rect.x));rect.y=Math.max(0,Math.min(source.height-1,rect.y));if(locked){const size=Math.max(1,Math.min(key==='height'?rect.height:rect.width,source.width-rect.x,source.height-rect.y));rect.width=rect.height=size;}else {rect.width=Math.max(1,Math.min(rect.width,source.width-rect.x));rect.height=Math.max(1,Math.min(rect.height,source.height-rect.y));}}
  function draw(){normalize();ctx.drawImage(picture,0,0,canvas.width,canvas.height);ctx.fillStyle='rgba(0,0,0,.55)';ctx.fillRect(0,0,canvas.width,canvas.height);ctx.save();ctx.beginPath();ctx.rect(rect.x*scale,rect.y*scale,rect.width*scale,rect.height*scale);ctx.clip();ctx.drawImage(picture,0,0,canvas.width,canvas.height);ctx.restore();ctx.strokeStyle='#2d6ef0';ctx.lineWidth=3;ctx.strokeRect(rect.x*scale,rect.y*scale,rect.width*scale,rect.height*scale);ctx.fillStyle='#2d6ef0';ctx.fillRect((rect.x+rect.width)*scale-6,(rect.y+rect.height)*scale-6,12,12);for(const el of dialog.querySelectorAll('[data-crop-field]'))el.value=rect[el.dataset.cropField];dialog.querySelector('[data-crop-size]').textContent='선택 결과 '+rect.width+'×'+rect.height+'px';}
  const point=event=>{const box=canvas.getBoundingClientRect();return {x:Math.round((event.clientX-box.left)/box.width*source.width),y:Math.round((event.clientY-box.top)/box.height*source.height)};};
  canvas.onpointerdown=event=>{if(working)return;const p=point(event),inside=p.x>=rect.x&&p.y>=rect.y&&p.x<=rect.x+rect.width&&p.y<=rect.y+rect.height,handle=Math.abs(p.x-rect.x-rect.width)<20/scale&&Math.abs(p.y-rect.y-rect.height)<20/scale;drag={point:p,before:{...rect},mode:handle?'resize':inside?'move':'new'};canvas.setPointerCapture(event.pointerId);event.preventDefault();};
  canvas.onpointermove=event=>{if(!drag||working)return;const p=point(event),dx=p.x-drag.point.x,dy=p.y-drag.point.y;if(drag.mode==='move')rect={...drag.before,x:Math.max(0,Math.min(source.width-drag.before.width,drag.before.x+dx)),y:Math.max(0,Math.min(source.height-drag.before.height,drag.before.y+dy))};else if(drag.mode==='resize')rect={...drag.before,width:drag.before.width+dx,height:drag.before.height+dy};else rect={x:Math.min(p.x,drag.point.x),y:Math.min(p.y,drag.point.y),width:Math.abs(dx),height:Math.abs(dy)};draw();};
  canvas.onpointerup=()=>drag=null;canvas.onpointercancel=()=>drag=null;
  canvas.onkeydown=event=>{if(working||!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key))return;event.preventDefault();const step=event.shiftKey?10:1;rect.x=Math.max(0,Math.min(source.width-rect.width,rect.x+(event.key==='ArrowLeft'?-step:event.key==='ArrowRight'?step:0)));rect.y=Math.max(0,Math.min(source.height-rect.height,rect.y+(event.key==='ArrowUp'?-step:event.key==='ArrowDown'?step:0)));draw();};
  for(const el of dialog.querySelectorAll('[data-crop-field]'))el.oninput=()=>{if(working)return;rect[el.dataset.cropField]=Number(el.value);normalize(el.dataset.cropField);draw();};
  dialog.querySelector('[data-crop-square]').onchange=event=>{locked=event.target.checked;draw();};draw();
  return await new Promise(resolve=>{
   const done=value=>{dialog.close();resolve(value);};dialog.oncancel=event=>{if(working)event.preventDefault();else done(null);};
   for(const key of ['close','cancel'])dialog.querySelector('[data-crop-'+key+']').onclick=()=>{if(!working)done(null);};
   dialog.querySelector('[data-crop-restore]').onclick=()=>{if(!working)done({url:source.originalUrl,assetId:null});};
   dialog.querySelector('[data-crop-confirm]').onclick=async()=>{if(working)return;working=true;error('');dialog.querySelectorAll('button,input').forEach(el=>el.disabled=true);try{const asset=await apiPost('/api/coupang/image-edits',{token:source.token,rect:{...rect}});done({assetId:asset.id,url:asset.url});}catch(err){error(err.message);}finally{working=false;dialog.querySelectorAll('button,input').forEach(el=>el.disabled=false);}};
  });
 }finally{dialog?.remove();active=false;}
}
function htmlImages(html){const doc=new DOMParser().parseFromString(html||'','text/html');return [...doc.querySelectorAll('img[src]')].map((img,index)=>({index,url:img.getAttribute('src')}));}
function replaceHtml(html,index,url){const doc=new DOMParser().parseFromString(html||'','text/html'),img=doc.querySelectorAll('img[src]')[index];if(!img)throw Error('편집할 설명 이미지를 다시 선택해 주세요.');img.setAttribute('src',url);return doc.body.innerHTML;}
function htmlButtons(html,attrs){return htmlImages(html).map(({index,url})=>`<button type="button" class="btn small image-html-edit" ${attrs(index)}><img src="${esc(url)}" alt="">설명 이미지 ${index+1} 편집</button>`).join('');}
window.ImageCropEditor={open,htmlImages,replaceHtml,htmlButtons};
})();
