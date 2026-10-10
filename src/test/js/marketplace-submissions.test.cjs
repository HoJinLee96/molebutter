const {test}=require('node:test'),assert=require('node:assert/strict');
const S=require('../../../molebutter-app/src/main/resources/static/js/marketplace-submissions.js');
const execution=(status,...states)=>({status,targets:[{steps:states.map(status=>({status}))}]});
test('standalone option changes use observed option name and readable price/stock labels',()=>{
    const draft={options:[{id:'internal-option-id',name:'브라운 FREE'}]};
    assert.equal(S.changeLabel('옵션.internal-option-id.options.price',draft),'옵션 브라운 FREE · 판매가');
    assert.equal(S.changeLabel('옵션.internal-option-id.options.quantity',draft),'옵션 브라운 FREE · 재고');
});
test('ambiguous registration cannot offer retry; reconciliation remains available',()=>{
    const e=execution('UNKNOWN','UNKNOWN');assert.equal(S.retryable(e),false);assert.equal(S.reconcilable(e),true);
    assert.equal(S.retryable(execution('PARTIAL','SUCCEEDED','FAILED','UNKNOWN')),false);
});
test('failed independent operations can retry after completion without repeating successful work',()=>{
    assert.equal(S.retryable(execution('PARTIAL','SUCCEEDED','FAILED')),true);
    assert.equal(S.retryable(execution('RUNNING','FAILED','QUEUED')),false);
    assert.equal(S.retryable(execution('SUCCEEDED','SUCCEEDED')),false);
    assert.equal(S.retryable({status:'FAILED',targets:[{steps:[{status:'FAILED',code:'BASELINE_CHANGED'}]}]}),false);
});
test('approval acceptance stays distinct from success and only offers result verification',()=>{
    const e=execution('ACCEPTED','ACCEPTED');assert.equal(S.active(e),false);assert.equal(S.reconcilable(e),true);assert.equal(S.retryable(e),false);
    assert.notEqual(S.statuses.ACCEPTED,S.statuses.SUCCEEDED);
});
test('change review identifies the option and field across registration and current-value requests',()=>{
    const draft={options:[{id:'stable-option-id',name:'블랙'}]};
    const spec=require('../../../molebutter-app/src/main/resources/static/js/marketplace-editor-model.js').spec;
    assert.equal(S.changeLabel('items.[1].salePrice',draft,spec),'옵션 블랙 · 등록 판매가');
    assert.equal(S.changeLabel('옵션.stable-option-id.salePrice',draft,spec),'옵션 블랙 · 현재 판매가');
    assert.equal(S.changeLabel('items.[1].images.[2].vendorPath',draft,spec),'옵션 블랙 · 이미지 2 · 이미지 주소');
    assert.equal(S.changeLabel('배송.returnCharge',draft,spec),'배송 · 반품 비용');
});

test('retry requires a replayable failure and the current saved draft revision',()=>{
 for(const code of ['ALREADY_REGISTERED','UNSUPPORTED_SNAPSHOT_VERSION','BASELINE_CHANGED'])assert.equal(S.retryable({status:'FAILED',revision:1,targets:[{steps:[{status:'FAILED',code}]}]},1),false);
 const failed={...execution('FAILED','FAILED'),revision:1};assert.equal(S.retryable(failed,2),false);assert.equal(S.retryable(failed,1),true);assert.equal(S.retryable({...failed,revised:true},1),false);
 assert.equal(S.retryable(execution('PARTIAL','FAILED','ACCEPTED')),false);
});
