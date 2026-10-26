'use strict';
const $ = (selector, root = document) => root.querySelector(selector);
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const num = value => new Intl.NumberFormat().format(value ?? 0);
const date = value => value ? new Date(value).toLocaleString([], {month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'}) : '—';
const state = {key:'',view:'dashboard',page:0,search:'',archived:false,warehouseId:'',low:false,rows:[],generation:0};
const views = {
 dashboard:['Overview','YOUR INVENTORY, AT A GLANCE','A clear view of what is in stock, and where.'],
 products:['Products','THE BUILDING BLOCKS','Every item, organized and easy to find.'],
 stock:['Inventory','KNOW WHAT YOU HAVE','Live stock balances across your warehouses.'],
 warehouses:['Warehouses','A PLACE FOR EVERYTHING','Keep every location connected.'],
 suppliers:['Suppliers','YOUR SUPPLY NETWORK','The people who keep your shelves stocked.'],
 movements:['Movements','EVERY CHANGE, ACCOUNTED FOR','A permanent record of stock coming in and going out.'],
 system:['System design','BUILT TO WORK TOGETHER','Explore the concepts behind your inventory system.']
};
let toastTimer;
function toast(message){$('#toast').textContent=message;$('#toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('#toast').hidden=true,4500);}
async function api(path, options={}) {
 const response=await fetch('/api/v1'+path,{...options,cache:'no-store',headers:{'Content-Type':'application/json','Authorization':'Bearer '+state.key,...options.headers}});
 $('#instance').textContent=response.headers.get('X-Instance-Id') || '';
 const body=await response.json().catch(()=>({message:'The server could not complete the request.'}));
 if(!response.ok){if(response.status===401){state.key='';$('#access-dialog').showModal();}throw new Error(body.message+(response.status===429?' Retry after '+response.headers.get('Retry-After')+' seconds.':''));}
 return body;
}
function panel(title,subtitle,body,action=''){return `<section class="panel"><div class="panel-heading"><div><h2>${esc(title)}</h2>${subtitle?`<p>${esc(subtitle)}</p>`:''}</div>${action}</div>${body}</section>`;}
function empty(title='Nothing here yet',description='Add your first record to get started.'){return `<div class="empty"><div class="empty-symbol">▦</div><strong>${esc(title)}</strong><p>${esc(description)}</p></div>`;}
function table(headers,rows){return `<div class="table-scroll"><table><thead><tr>${headers.map(h=>`<th scope="col">${esc(h)}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table></div>`;}
function metric(label,value,caption,icon,warm=false){return `<article class="metric${warm?' warm':''}"><div class="metric-top">${esc(label)}<span class="metric-icon" aria-hidden="true">${icon}</span></div><strong class="metric-value">${num(value)}</strong><span class="metric-caption">${esc(caption)}</span></article>`;}
function pagination(data){return `<div class="pagination"><span>${num(data.total)} records · Page ${data.page+1}</span><div><button class="button small" data-page="${data.page-1}" ${data.page===0?'disabled':''}>Previous</button><button class="button small" data-page="${data.page+1}" ${(data.page+1)*data.size>=data.total?'disabled':''}>Next</button></div></div>`;}
function stockTable(data){if(!data.items.length)return empty('No stock balances','Receive stock to start tracking a product at a warehouse.');return table(['Product','Warehouse','On hand','Reorder at','Status'],data.items.map(r=>`<tr><td><span class="cell-name">${esc(r.productName)}</span><span class="cell-sub mono">${esc(r.sku)}</span></td><td>${esc(r.warehouseName)}</td><td class="number">${num(r.quantity)}</td><td class="mono">${num(r.reorderLevel)}</td><td><span class="pill ${r.lowStock?'warn':''}">${r.lowStock?'Low stock':'In stock'}</span></td></tr>`));}
function movementTable(data){if(!data.items.length)return empty('No movements yet','Stock receipts, issues, and transfers will appear here.');return table(['Product','Action','Quantity','Route','When'],data.items.map(r=>`<tr><td><span class="cell-name">${esc(r.productName)}</span><span class="cell-sub mono">${esc(r.sku)}</span></td><td><span class="pill ${r.type==='ISSUE'?'warn':r.type==='TRANSFER'?'neutral':''}">${esc(r.type.toLowerCase())}</span></td><td class="number">${num(r.quantity)}</td><td>${esc(r.warehouseName)}${r.destinationWarehouseName?`<span class="cell-sub">→ ${esc(r.destinationWarehouseName)}</span>`:''}</td><td class="mono">${esc(date(r.createdAt))}</td></tr>`));}
function actions(){if(['products','warehouses','suppliers'].includes(state.view))return `<button class="button primary" data-action="add">＋ Add ${state.view.slice(0,-1)}</button>`;if(['dashboard','stock','movements'].includes(state.view))return '<button class="button primary" data-action="stock">＋ Stock action</button>';return '<button class="button" data-action="refresh">↻ Refresh</button>';}
async function load(){
 const generation=++state.generation;
 const [title,eyebrow,subtitle]=views[state.view];$('#title').textContent=title;$('#breadcrumb').textContent=title;$('#eyebrow').textContent=eyebrow;$('#subtitle').textContent=subtitle;$('#page-actions').innerHTML=actions();
 document.querySelectorAll('[data-view]').forEach(b=>b.classList.toggle('active',b.dataset.view===state.view));
 $('#view').innerHTML='<div class="loading">Loading your workspace…</div>';
 try{
 let html='';
 if(state.view==='dashboard'){
 const [data,low]=await Promise.all([api('/dashboard'),api('/stock?low=true&size=5')]);
 html=`<div class="metrics">${metric('Active products',data.products,'Across your catalog','▦')}${metric('Total units',data.units,'Tracked in your warehouses','▤')}${metric('Warehouses',data.warehouses,'Connected locations','⌂')}${metric('Low-stock items',data.lowStock,'Tracked balances at reorder level','↘',true)}</div>`;
 html+=panel('Recent movements','The latest changes across your inventory.',movementTable({items:data.recentMovements}),'<button class="text-button" data-view="movements">View all →</button>');
 html+=`<div class="two-columns">${panel('Needs your attention','Products that have reached their reorder level.',stockTable(low))}<aside><div class="note-card"><p class="eyebrow">CONNECTED WAREHOUSES</p><h2>A shared view. A smaller link.</h2><p>Give each manager a read-only view of their warehouse. Create a private link from the Warehouses screen and revoke it at any time.</p><button class="button" data-view="warehouses">Manage warehouses ↗</button></div><div class="note-card"><p class="eyebrow">CONFIDENT STOCK COUNTS</p><h2>Every movement has a record.</h2><p>Receipts, issues, and transfers update stock together with their history. Repeated requests keep the same result.</p></div></aside></div>`;
 } else if(['products','warehouses','suppliers'].includes(state.view)){
 const data=await api(`/${state.view}?page=${state.page}&search=${encodeURIComponent(state.search)}&archived=${state.archived}`);state.rows=data.items;
 html=`<div class="toolbar"><input id="search" class="search-input" aria-label="Search records" placeholder="Search ${state.view}…" value="${esc(state.search)}"><label class="check"><input id="archived" type="checkbox" ${state.archived?'checked':''}>Show archived</label></div>`;
 let headers;let rows;
 const rowActions=r=>`<div class="row-actions">${!state.archived?`<button class="text-button" data-edit="${esc(r.id)}">Edit</button>${state.view==='warehouses'?`<button class="text-button" data-links="${esc(r.id)}">Share link ↗</button>`:''}<button class="text-button" data-archive="${esc(r.id)}">Archive</button>`:'<span class="pill neutral">Archived</span>'}</div>`;
 if(state.view==='products'){headers=['Product','Category','Supplier ID','Reorder at',''];rows=data.items.map(r=>`<tr><td><span class="cell-name">${esc(r.name)}</span><span class="cell-sub mono">${esc(r.sku)}</span></td><td>${esc(r.category)}</td><td class="mono">${esc(r.supplierId)}</td><td class="number">${num(r.reorderLevel)}</td><td>${rowActions(r)}</td></tr>`);}
 if(state.view==='warehouses'){headers=['Warehouse','Location','Status',''];rows=data.items.map(r=>`<tr><td><span class="cell-name">${esc(r.name)}</span><span class="cell-sub mono">${esc(r.code)}</span></td><td>${esc(r.location)}</td><td><span class="pill ${r.active?'':'neutral'}">${r.active?'Active':'Archived'}</span></td><td>${rowActions(r)}</td></tr>`);}
 if(state.view==='suppliers'){headers=['Supplier','Email','Phone',''];rows=data.items.map(r=>`<tr><td><span class="cell-name">${esc(r.name)}</span><span class="cell-sub mono">#${esc(r.id)}</span></td><td>${esc(r.email)||'—'}</td><td>${esc(r.phone)||'—'}</td><td>${rowActions(r)}</td></tr>`);}
 html+=panel(title,`${state.archived?'Archived':'Active'} records in your workspace.`,rows.length?table(headers,rows)+pagination(data):empty('No matching '+state.view,state.archived?'Archived records will appear here.':'Use the add button to create your first record.'));
 } else if(state.view==='stock'||state.view==='movements'){
 const [data,warehouses]=await Promise.all([api(`/${state.view==='stock'?'stock':'movements'}?page=${state.page}&warehouseId=${encodeURIComponent(state.warehouseId)}&low=${state.low}`),api('/warehouses?size=100')]);
 html=`<div class="toolbar"><select id="warehouse-filter" aria-label="Filter by warehouse"><option value="">All warehouses</option>${warehouses.items.map(w=>`<option value="${esc(w.id)}" ${w.id===state.warehouseId?'selected':''}>${esc(w.name)}</option>`).join('')}</select>${state.view==='stock'?`<label class="check"><input id="low-filter" type="checkbox" ${state.low?'checked':''}>Low stock only</label>`:''}</div>`;
 html+=panel(title,state.view==='stock'?'Current balances. Quantities are read from MongoDB.':'A complete history of stock operations.',(state.view==='stock'?stockTable(data):movementTable(data))+pagination(data));
 } else {
 const data=await api('/system');
 html=`<div class="note-card"><p class="eyebrow">HORIZONTAL SCALING</p><h2>One workspace, two app instances.</h2><p>Nginx balances requests between identical app instances. Both use MongoDB for stock and Redis for the shared token bucket. Refresh to see which instance responds. This screen reports the current instance’s cache counters.</p></div><div class="system-grid">`;
 html+=panel('Service health','Dependency checks from this app instance.',`<div class="panel-content status-list"><div class="status-row"><span>Application instance</span><span class="mono">${esc(data.instanceId)}</span></div>${statusRow('MongoDB',data.mongo)}${statusRow('Rate-limit Redis',data.rateLimiter.healthy)}${data.cacheNodes.map(n=>statusRow(n.address,n.healthy)).join('')}</div>`);
 html+=panel('Token bucket','One shared bucket per client IP.',`<div class="panel-content status-list"><div class="status-row"><span>Burst capacity</span><span class="stat-inline">${num(data.rateLimiter.capacity)} tokens</span></div><div class="status-row"><span>Refill rate</span><span class="stat-inline">${num(data.rateLimiter.refillPerSecond)} / second</span></div><p class="muted">Each API request consumes one token. Empty buckets return HTTP 429 and a Retry-After header.</p></div>`);
 html+=panel('Consistent hashing','128 virtual nodes per cache node.',table(['Search prefix','Selected cache node'],data.cache.routing.map(r=>`<tr><td>${esc(r.prefix)}</td><td class="mono">${esc(r.node)}</td></tr>`)));
 html+=panel('Key-value cache','Autocomplete results expire after 15 seconds.',`<div class="panel-content status-list"><div class="status-row"><span>Cache hits</span><strong>${num(data.cache.hits)}</strong></div><div class="status-row"><span>Cache misses</span><strong>${num(data.cache.misses)}</strong></div><div class="status-row"><span>Cache failures</span><strong>${num(data.cache.failures)}</strong></div><p class="muted">Unavailable cache nodes fall back to MongoDB. Stock and link authorization are never cached.</p></div>`);
 html+='</div>';
 }
 if(generation===state.generation)$('#view').innerHTML=html;
 }catch(error){if(generation===state.generation)$('#view').innerHTML=`<div class="error-panel" role="alert">${esc(error.message)} <button class="text-button" data-action="refresh">Try again →</button></div>`;}
}
function statusRow(name,healthy){return `<div class="status-row"><span>${esc(name)}</span><span class="pill ${healthy?'':'red'}">${healthy?'Available':'Unavailable'}</span></div>`;}
function navigate(view){state.view=view;state.page=0;state.search='';state.archived=false;state.warehouseId='';state.low=false;load();}
function field(label,name,value='',type='text',extra=''){return `<label>${esc(label)}<input name="${name}" type="${type}" value="${esc(value)}" ${extra}></label>`;}
function lookup(label,name,type,value=''){return `<label>${esc(label)}<input class="lookup-search" type="search" data-lookup="${type}" data-target="${name}" aria-label="Search ${esc(label.toLowerCase())}" placeholder="Search by name…"><select aria-label="${esc(label)}" name="${name}" data-source="${type}" data-current="${esc(value)}" required><option value="">Select ${esc(label.toLowerCase())}</option></select></label>`;}
function productLookup(){return `<fieldset class="product-lookup"><legend>Product</legend><label class="product-search-label" for="product-search">Search by name or SKU</label><input id="product-search" type="search" maxlength="80" placeholder="e.g. pr or BRG" autocomplete="off" aria-describedby="product-search-status" required><p id="product-search-status" class="suggestion-status" role="status">Type at least 2 characters to see up to 3 suggestions.</p><div class="product-suggestions" role="group" aria-label="Product suggestions"></div></fieldset>`;}
const productRequests=new WeakMap();
function resetProductSuggestions(input){
 const lookup=input.closest('.product-lookup');productRequests.set(lookup,(productRequests.get(lookup)||0)+1);
 input.setCustomValidity('Select a product from the suggestions.');
 $('.product-suggestions',lookup).replaceChildren();
 $('.product-suggestions',lookup).setAttribute('aria-busy','false');
 $('.suggestion-status',lookup).textContent=input.value.trim().length<2?'Type at least 2 characters to see up to 3 suggestions.':'Searching products…';
}
async function fillProductSuggestions(input){
 const lookup=input.closest('.product-lookup');const search=input.value.trim();if(search.length<2)return;
 const request=productRequests.get(lookup);const suggestions=$('.product-suggestions',lookup);suggestions.setAttribute('aria-busy','true');
 try{
 const items=(await api(`/autocomplete?prefix=${encodeURIComponent(search)}`)).slice(0,3);
 if(!lookup.isConnected||productRequests.get(lookup)!==request)return;
 suggestions.innerHTML=items.map(r=>`<label class="product-suggestion"><input type="radio" name="productId" value="${esc(r.id)}" required><span class="suggestion-content"><span class="suggestion-name">${esc(r.name)}</span><span class="suggestion-sku">${esc(r.sku)}</span></span></label>`).join('');
 $('.suggestion-status',lookup).textContent=items.length?`${items.length} suggestion${items.length===1?'':'s'}. Select a product below.`:'No matching products. Try the start of a name or SKU.';
 }catch(error){if(lookup.isConnected&&productRequests.get(lookup)===request)$('.suggestion-status',lookup).textContent=error.message;}
 finally{if(productRequests.get(lookup)===request)suggestions.setAttribute('aria-busy','false');}
}
function openDialog(title,body){$('#form-title').textContent=title;$('#form-body').innerHTML=body;if(!$('#form-dialog').open)$('#form-dialog').showModal();}
async function fillLookup(select,search=''){
 const result=await api(select.dataset.source==='products'&&search.trim().length>=2?`/autocomplete?prefix=${encodeURIComponent(search.trim())}`:`/${select.dataset.source}?size=100&search=${encodeURIComponent(search)}`);const data=Array.isArray(result)?{items:result}:result;if(!select.isConnected)return;
 const current=select.value||select.dataset.current;select.innerHTML='<option value="">Select a record</option>'+data.items.map(r=>`<option value="${esc(r.id)}">${esc(r.name)}${r.sku?' · '+esc(r.sku):r.code?' · '+esc(r.code):''}</option>`).join('');
 if(current&&!data.items.some(r=>r.id===current))select.insertAdjacentHTML('beforeend',`<option value="${esc(current)}">Current record #${esc(current)}</option>`);
 select.value=current||'';
}
async function openRecord(row){
 const type=state.view;let fields='';
 if(type==='products')fields=field('Product name','name',row?.name,'text','required maxlength="120"')+`<div class="form-grid">${field('SKU','sku',row?.sku,'text','required maxlength="64"')}${field('Category','category',row?.category,'text','required maxlength="80"')}</div>`+lookup('Supplier','supplierId','suppliers',row?.supplierId)+field('Reorder level','reorderLevel',row?.reorderLevel??5,'number','required min="0" max="1000000000" step="1"');
 if(type==='warehouses')fields=field('Warehouse name','name',row?.name,'text','required maxlength="120"')+field('Warehouse code','code',row?.code,'text','required maxlength="40"')+field('Location','location',row?.location,'text','required maxlength="200"');
 if(type==='suppliers')fields=field('Supplier name','name',row?.name,'text','required maxlength="120"')+field('Email','email',row?.email,'email','maxlength="200"')+field('Phone','phone',row?.phone,'text','maxlength="40"');
 openDialog(`${row?'Edit':'Add'} ${type.slice(0,-1)}`,`<form id="record-form">${fields}<p class="error" id="form-error" role="alert"></p><div class="form-actions"><button class="button" type="button" data-close>Cancel</button><button class="button primary" type="submit">Save ${type.slice(0,-1)}</button></div></form>`);
 document.querySelectorAll('#record-form select[data-source]').forEach(s=>fillLookup(s).catch(showFormError));
 $('#record-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;const data=Object.fromEntries(new FormData(event.target));if(type==='products')data.reorderLevel=Number(data.reorderLevel);try{await api('/'+type+(row?'/'+encodeURIComponent(row.id):''),{method:row?'PUT':'POST',body:JSON.stringify(data)});$('#form-dialog').close();toast('Record saved.');load();}catch(error){showFormError(error);}finally{button.disabled=false;}});
}
function showFormError(error){const target=$('#form-error');if(target)target.textContent=error.message;else toast(error.message);}
async function openStock(){
 const requestKey=crypto.randomUUID();let pendingBody=null;
 openDialog('Record a stock action',`<form id="stock-form"><label>Action<select name="operation" id="stock-operation"><option value="receipts">Receive stock</option><option value="issues">Issue stock</option><option value="transfers">Transfer stock</option></select></label>${productLookup()}${lookup('Warehouse','warehouseId','warehouses')}<div id="destination" hidden>${lookup('Destination warehouse','destinationWarehouseId','warehouses')}</div>${field('Quantity','quantity',1,'number','required min="1" max="1000000000" step="1"')}<label>Note<textarea name="note" maxlength="400" placeholder="Optional reference or reason"></textarea></label><p class="inline-note">The same request key is kept if a submission must be retried. Quantities are whole units.</p><p class="error" id="form-error" role="alert"></p><div class="form-actions"><button class="button" type="button" data-close>Cancel</button><button class="button primary" type="submit">Record movement</button></div></form>`);
 resetProductSuggestions($('#product-search'));
 $('#product-search').addEventListener('input',event=>{const input=event.target;clearTimeout(lookupTimers.get(input));resetProductSuggestions(input);lookupTimers.set(input,setTimeout(()=>fillProductSuggestions(input),250));});
 $('.product-suggestions').addEventListener('change',event=>{if(event.target.name==='productId'){$('#product-search').setCustomValidity('');$('#product-search-status').textContent='Product selected. You can now record the stock action.';}});
 $('#destination select').required=false;
 document.querySelectorAll('#stock-form select[data-source]').forEach(s=>fillLookup(s).catch(showFormError));
 $('#stock-operation').addEventListener('change',event=>{const transfer=event.target.value==='transfers';$('#destination').hidden=!transfer;$('#destination select').required=transfer;});
 $('#stock-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;const data=Object.fromEntries(new FormData(event.target));const operation=data.operation;delete data.operation;data.quantity=Number(data.quantity);if(operation!=='transfers')delete data.destinationWarehouseId;const serialized=JSON.stringify({operation,...data});if(pendingBody&&pendingBody!==serialized){showFormError(new Error('Retry the original values, or close this form and start a new action.'));button.disabled=false;return;}pendingBody=serialized;try{await api('/stock/'+operation,{method:'POST',headers:{'Idempotency-Key':requestKey},body:JSON.stringify(data)});$('#form-dialog').close();toast('Stock movement recorded.');load();}catch(error){showFormError(error);}finally{button.disabled=false;}});
}
async function openLinks(id,page=0){
 const warehouse=state.rows.find(r=>r.id===id);openDialog('Share '+warehouse.name,'<div class="loading">Loading links…</div>');
 try{
 const data=await api(`/warehouses/${encodeURIComponent(id)}/links?size=20&page=${page}`);
 $('#form-body').innerHTML=`<p class="muted">Anyone with a link can read this warehouse’s stock and movements. Links remain active until revoked. Copy a new link now; its secret is not stored.</p><form id="link-form">${field('Link label','label','','text','required maxlength="80" placeholder="e.g. Warehouse manager"')}<button class="button primary" type="submit">Generate short link ↗</button><p class="error" id="form-error" role="alert"></p></form><div id="link-result"></div><h2>Existing links</h2><div id="existing-links">${data.items.length?table(['Label','Status',''],data.items.map(r=>`<tr><td>${esc(r.label)}<span class="cell-sub">${esc(date(r.createdAt))}</span></td><td><span class="pill ${r.active?'':'neutral'}">${r.active?'Active':'Revoked'}</span></td><td>${r.active?`<button class="text-button" data-revoke="${esc(r.id)}" data-warehouse="${esc(id)}">Revoke</button>`:''}</td></tr>`)):empty('No links yet','Generate a link for this warehouse manager.')}</div><div id="link-pagination" data-warehouse="${esc(id)}">${pagination(data).replaceAll('data-page=', 'data-link-page=')}</div>`;
 $('#link-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;try{const result=await api(`/warehouses/${encodeURIComponent(id)}/links`,{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(event.target)))});$('#link-result').innerHTML=`<div class="note-card link-result"><label>New warehouse link<input id="new-link" readonly value="${esc(result.url)}"></label><button class="button" type="button" id="copy-link">Copy link</button><a class="button" href="${esc(result.url)}" target="_blank" rel="noreferrer">Open view ↗</a><p>Copy this link before you close the dialog.</p></div>`;$('#copy-link').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(result.url);toast('Link copied.');}catch{$('#new-link').select();toast('Select and copy the link.');}});const tableBody=$('#existing-links tbody');const newRow=`<tr><td>${esc(result.link.label)}<span class="cell-sub">Just created</span></td><td><span class="pill">Active</span></td><td><button class="text-button" data-revoke="${esc(result.link.id)}" data-warehouse="${esc(id)}">Revoke</button></td></tr>`;if(tableBody)tableBody.insertAdjacentHTML('afterbegin',newRow);else $('#existing-links').innerHTML=table(['Label','Status',''],[newRow]);}catch(error){showFormError(error);}finally{button.disabled=false;}});
 }catch(error){$('#form-body').innerHTML=`<p class="error">${esc(error.message)}</p>`;}
}
document.addEventListener('click',async event=>{
 const target=event.target.closest('button');if(!target)return;
 if(target.dataset.view){navigate(target.dataset.view);return;}
 if(target.hasAttribute('data-close')){$('#form-dialog').close();return;}
 if(target.dataset.linkPage!==undefined){openLinks(target.closest('[data-warehouse]').dataset.warehouse,Number(target.dataset.linkPage));return;}
 if(target.dataset.page!==undefined){state.page=Number(target.dataset.page);load();return;}
 if(target.dataset.action==='refresh'){load();return;}
 if(target.dataset.action==='add'){openRecord();return;}
 if(target.dataset.action==='stock'){openStock();return;}
 if(target.dataset.edit){openRecord(state.rows.find(r=>r.id===target.dataset.edit));return;}
 if(target.dataset.links){openLinks(target.dataset.links);return;}
 if(target.dataset.archive){const id=target.dataset.archive;openDialog('Archive this record?',`<p class="muted">The record will leave the active list. Its movement history will remain. Records with stock cannot be archived.</p><p class="error" id="form-error" role="alert"></p><div class="form-actions"><button class="button" data-close>Cancel</button><button class="button danger" id="confirm-archive">Archive record</button></div>`);$('#confirm-archive').addEventListener('click',async()=>{try{await api(`/${state.view}/${encodeURIComponent(id)}/archive`,{method:'POST'});$('#form-dialog').close();toast('Record archived.');load();}catch(error){showFormError(error);}});return;}
 if(target.dataset.revoke){target.disabled=true;try{await api(`/warehouses/${encodeURIComponent(target.dataset.warehouse)}/links/${encodeURIComponent(target.dataset.revoke)}`,{method:'DELETE'});target.closest('tr').querySelector('.pill').textContent='Revoked';target.remove();$('#link-result').innerHTML='';toast('Link revoked.');}catch(error){toast(error.message);target.disabled=false;}}
});
let searchTimer;const lookupTimers=new WeakMap();
document.addEventListener('input',event=>{if(event.target.id==='search'){clearTimeout(searchTimer);const value=event.target.value;searchTimer=setTimeout(()=>{state.search=value;state.page=0;load().then(()=>{const input=$('#search');if(input){input.focus();input.setSelectionRange(value.length,value.length);}});},300);}if(event.target.dataset.lookup){const input=event.target;clearTimeout(lookupTimers.get(input));lookupTimers.set(input,setTimeout(()=>{const select=input.closest('label').querySelector('select');fillLookup(select,input.value).catch(showFormError);},250));}});
document.addEventListener('change',event=>{if(event.target.id==='archived'){state.archived=event.target.checked;state.page=0;load();}if(event.target.id==='warehouse-filter'){state.warehouseId=event.target.value;state.page=0;load();}if(event.target.id==='low-filter'){state.low=event.target.checked;state.page=0;load();}});
$('#access-dialog').addEventListener('cancel',event=>event.preventDefault());
$('#access-form').addEventListener('submit',async event=>{event.preventDefault();const button=event.submitter;button.disabled=true;state.key=$('#owner-key').value;try{await api('/dashboard');$('#owner-key').value='';$('#access-error').textContent='';$('#access-dialog').close();load();}catch(error){state.key='';$('#access-error').textContent=error.message;}finally{button.disabled=false;}});
$('#lock').addEventListener('click',()=>{state.key='';state.generation++;state.rows=[];$('#view').innerHTML='';$('#form-dialog').close();$('#access-dialog').showModal();});
$('#access-dialog').showModal();
