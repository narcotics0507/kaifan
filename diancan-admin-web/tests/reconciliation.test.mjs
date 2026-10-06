import test from 'node:test';
import assert from 'node:assert/strict';
import { difference, hasDifference, reconciliationCsv, validActual } from '../src/views/report/revenue/reconciliation.mjs';
test('Explicit zero and refund values are valid; blank and fractional cents are rejected',()=>{for(const v of [0,-2,0.29,1234567890.29])assert(validActual(v));for(const v of [null,NaN,Infinity,1.001,0.0000001,10000000000])assert(!validActual(v));});
test('Per-channel mismatch cannot be canceled by an equal total',()=>{const system={wechat:10,alipay:20,cash:0,other:0};assert(hasDifference({...system,wechat:20,alipay:10},system));assert.equal(difference(0.3,0.1),0.2);assert.equal(difference(null,10),null);assert(!hasDifference(system,system));});
test('Refund-only day accepts negative net receipts',()=>{assert.equal(difference(-20,-20),0);assert.equal(difference(0,-20),20);});
test('Export includes preserved snapshot and blocks text formula injection',()=>{const row={revision:1,savedAt:'2026-10-06',operator:'=1+1',system:{wechat:10,alipay:0,cash:-2,other:0},actual:{wechat:11,alipay:0,cash:-2,other:0},reason:'退款,核对"备注'};const csv=reconciliationCsv('2026-10-06',[row]);assert(csv.startsWith('\uFEFF'));assert(csv.includes("'="));assert(csv.includes('退款,核对""备注'));assert.equal(csv.split('\r\n').length,5);assert(csv.includes('"-2"'));});
