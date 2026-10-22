#!/usr/bin/env python3
import json,urllib.request,urllib.error
from pathlib import Path
from integration import login,BASE
results=[]
def rpc(token,body,session=None,notification=False):
 headers={'Authorization':'Bearer '+token,'Accept':'application/json, text/event-stream','Content-Type':'application/json','MCP-Protocol-Version':'2025-03-26'}
 if session:headers['Mcp-Session-Id']=session
 req=urllib.request.Request(BASE+'/mcp',json.dumps(body).encode(),headers)
 with urllib.request.urlopen(req,timeout=15) as res:
  sid=res.headers.get('Mcp-Session-Id',session)
  if notification or res.status in(202,204):return None,sid
  if 'text/event-stream' in res.headers.get('Content-Type',''):
   for line in res:
    if line.startswith(b'data:'):return json.loads(line[5:]),sid
  return json.load(res),sid

def client(name,pw):
 token=login(name,pw)
 init,sid=rpc(token,{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'assets-local-verification','version':'1'}}})
 assert init['result']['serverInfo']['name']=='aidevops-assets'
 rpc(token,{'jsonrpc':'2.0','method':'notifications/initialized'},sid,True)
 return token,sid

a,sid=client('admin','123456')
catalog,_=rpc(a,{'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}},sid)
assert 'assets_search' in [t['name'] for t in catalog['result']['tools']]
results.append({'name':'official_sdk_handshake_and_catalog','status':'PASS'})
r,_=rpc(a,{'jsonrpc':'2.0','id':3,'method':'tools/call','params':{'name':'assets_search','arguments':{'projectId':'demo'}}},sid)
assert r['result']['isError']==False,r
results.append({'name':'authorized_tool_uses_same_project_service','status':'PASS'})
v,vsid=client('developer','Demo-local-2026!')
r,_=rpc(v,{'jsonrpc':'2.0','id':4,'method':'tools/call','params':{'name':'assets_create','arguments':{'projectId':'demo','name':'must-deny','type':'SERVER','mode':'SIMULATED'}}},vsid)
assert r['result']['isError']==True and 'FORBIDDEN' in json.dumps(r),r
results.append({'name':'readonly_mcp_mutation_rejected','status':'PASS'})
r,_=rpc(v,{'jsonrpc':'2.0','id':5,'method':'tools/call','params':{'name':'assets_search','arguments':{'projectId':'sandbox'}}},vsid)
assert r['result']['isError']==True and 'FORBIDDEN' in json.dumps(r),r
results.append({'name':'mcp_cross_project_scope_rejected','status':'PASS'})
Path('docs/test-results/mcp.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
