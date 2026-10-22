#!/usr/bin/env python3
"""Local Compose integration checks. Uses development Keycloak grants; never stores tokens."""
import os,json,time,uuid,urllib.request,urllib.parse,urllib.error,datetime,sys
from pathlib import Path
BASE=os.getenv('ASSETS_API','http://localhost:19382')
IDP=os.getenv('ASSETS_IDP','http://localhost:19383')
RESULT=[]
RUN=uuid.uuid4().hex[:8]
def login(name,password):
    body=urllib.parse.urlencode({'client_id':'assets-web','grant_type':'password','username':name,'password':password,'scope':'openid profile email'}).encode()
    with urllib.request.urlopen(IDP+'/realms/assets/protocol/openid-connect/token',body,timeout=30) as r:return json.load(r)['access_token']
def call(path,method='GET',data=None,token=None,expected=(200,201,202,204),headers=None):
    h=dict(headers or {})
    if token:h['Authorization']='Bearer '+token
    if data is not None and not isinstance(data,bytes):data=json.dumps(data).encode();h['Content-Type']='application/json'
    req=urllib.request.Request(BASE+path,data=data,headers=h,method=method)
    try:
        with urllib.request.urlopen(req,timeout=30) as r:status=r.status;raw=r.read();rh=dict(r.headers)
    except urllib.error.HTTPError as e:status=e.code;raw=e.read();rh=dict(e.headers)
    if status not in expected:raise AssertionError(f'{method} {path}: expected {expected}, got {status}; '+raw.decode(errors='replace')[:500])
    if not raw:return None
    if 'application/json' in rh.get('Content-Type',''):return json.loads(raw)
    return raw
A=None
V=None
def api(path,method='GET',data=None,**kw):return call('/api/v1'+path,method,data,token=kw.pop('token',A),**kw)
def check(name,fn):
    start=time.monotonic()
    try:value=fn();RESULT.append({'name':name,'status':'PASS','seconds':round(time.monotonic()-start,3)});print('PASS',name);return value
    except Exception as e:RESULT.append({'name':name,'status':'FAIL','error':str(e)});print('FAIL',name,str(e));raise

def main():
    global A,V
    check('unauthenticated_api_rejected',lambda:call('/api/v1/auth/me',expected=(401,)))
    A=login('admin',os.getenv('ASSETS_ADMIN_PASSWORD','123456'))
    V=login('developer',os.getenv('ASSETS_VIEWER_PASSWORD','Demo-local-2026!'))
    profile=check('keycloak_identity_and_project_grants',lambda:api('/auth/me'))
    assert profile['subject']=='11111111-1111-4111-8111-111111111111' and 'demo' in profile['projects']
    check('invalid_token_rejected',lambda:call('/api/v1/auth/me',token='not-a-token',expected=(401,)))
    check('cross_project_read_denied',lambda:api('/assets?projectId=sandbox',token=V,expected=(403,)))
    check('readonly_write_denied',lambda:api('/assets','POST',{'projectId':'demo','type':'SERVER','name':'denied','mode':'SIMULATED'},token=V,expected=(403,)))
    found=check('simulated_discovery_persisted',lambda:api('/assets/discover','POST',{'projectId':'demo','mode':'SIMULATED'}))
    repeated=api('/assets/discover','POST',{'projectId':'demo','mode':'SIMULATED'});assert repeated['created']==0
    items=api('/assets?projectId=demo&pageSize=200')['items'];server=next(a for a in items if a['type']=='SERVER' and a['externalId']=='fixture-server')
    server=check('version_bound_asset_admission',lambda:api('/assets/'+server['id']+'/admit','POST',{'projectId':'demo','version':server['version']}))
    check('stale_asset_version_rejected',lambda:api('/assets/'+server['id'],'PUT',{'projectId':'demo','version':-1,'name':'stale'},expected=(409,)))
    check('real_discovery_not_configured',lambda:api('/assets/discover','POST',{'projectId':'sandbox','mode':'REAL'},expected=(503,)))
    created=check('asset_full_fields_persisted',lambda:api('/assets','POST',{'projectId':'demo','type':'SOFTWARE','name':'=1+2 '+RUN,'mode':'SIMULATED','owner':'ops','technical':{'softwareVersion':'1.0'},'operation':{'status':'维护中'},'lifecycle':{'purchaseDate':'2026-10-22'}}))
    assert api('/assets/'+created['id']+'?projectId=demo')['operation']['status']=='维护中'
    mac=':'.join(RUN[i:i+2] for i in range(0,8,2))+':ab:cd'
    hardware={'projectId':'demo','type':'NETWORK','name':'硬件身份-'+RUN,'mode':'SIMULATED','technical':{'serial':'SERIAL-'+RUN,'mac':mac}}
    check('physical_serial_and_mac_persisted',lambda:api('/assets','POST',hardware))
    check('duplicate_serial_rejected',lambda:api('/assets','POST',{**hardware,'name':'重复序列号','technical':{'serial':'serial-'+RUN}},expected=(409,)))
    check('normalized_mac_conflict_rejected',lambda:api('/assets','POST',{**hardware,'name':'重复MAC','technical':{'mac':mac.upper().replace(':','-')}},expected=(409,)))
    check('invalid_mac_rejected',lambda:api('/assets','POST',{**hardware,'technical':{'mac':'invalid'}},expected=(400,)))
    check('same_name_different_resource_allowed',lambda:api('/assets','POST',{'projectId':'demo','type':'SOFTWARE','name':created['name'],'mode':'SIMULATED'}))
    api('/assets/'+created['id']+'/admit','POST',{'projectId':'demo','version':created['version']})
    relation=check('relationship_creation',lambda:api('/relationships','POST',{'projectId':'demo','sourceId':created['id'],'targetId':server['id'],'type':'DEPLOYED_TO'}))
    check('relationship_version_delete',lambda:api('/relationships/'+relation['id']+'?projectId=demo&version='+str(relation['version']),'DELETE'))
    preview=check('sync_diff_preview',lambda:api('/assets/sync/preview','POST',{'projectId':'demo','mode':'SIMULATED','assetIds':[server['id']]}))
    changed=api('/assets/'+server['id'],'PUT',{'projectId':'demo','version':server['version'],'owner':'manual-'+RUN})
    check('sync_stale_preview_rejected',lambda:api('/assets/sync/confirm','POST',{'projectId':'demo','previewId':preview['previewId']},expected=(409,)))
    preview=api('/assets/sync/preview','POST',{'projectId':'demo','mode':'SIMULATED','assetIds':[server['id']]})
    check('sync_confirm_preserves_manual_owner',lambda:api('/assets/sync/confirm','POST',{'projectId':'demo','previewId':preview['previewId']}))
    server=api('/assets/'+server['id']+'?projectId=demo');assert server['owner']=='manual-'+RUN
    exported=check('csv_formula_injection_escaped',lambda:api('/assets/export?projectId=demo&format=csv'))
    assert b"'=1+2" in exported
    check('xlsx_export_generated',lambda:api('/assets/export?projectId=demo&format=xlsx'))
    csv=('name,type,mode\n导入-'+RUN+',CLIENT,SIMULATED\n无效,INVALID,SIMULATED\n').encode()
    boundary='assets-'+RUN
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="projectId"\r\n\r\ndemo\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="assets.csv"\r\nContent-Type: text/csv\r\n\r\n').encode()+csv+f'\r\n--{boundary}--\r\n'.encode()
    imp=check('csv_import_row_validation',lambda:api('/assets/import/preview','POST',body,headers={'Content-Type':'multipart/form-data; boundary='+boundary}));assert imp['validCount']==1 and imp['errorCount']==1
    applied=check('partial_import_truthful_result',lambda:api('/assets/import/confirm','POST',{'projectId':'demo','previewId':imp['previewId']}));assert applied['applied']==1 and applied['rejected']==1
    idem=api('/assets/import/confirm','POST',{'projectId':'demo','previewId':imp['previewId']});assert idem==applied
    rule=api('/alarm-rules','POST',{'projectId':'demo','name':'CPU测试-'+RUN,'source':'HARDWARE','severity':'WARNING','metric':'CPU','operator':'GT','threshold':80,'durationSeconds':0,'enabled':True,'targetIds':[server['id']],'notificationChannels':['IN_APP','EMAIL']})
    event={'projectId':'demo','assetId':server['id'],'mode':'SIMULATED','externalEventId':'test-'+RUN,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),'metrics':{'CPU':95,'ONLINE':1}}
    check('observation_creates_real_persisted_simulation_alarm',lambda:api('/observations','POST',event))
    assert api('/observations','POST',event)['status']=='DUPLICATE'
    alarm=next(a for a in api('/alarms?projectId=demo') if a['ruleId']==rule['id'])
    check('alarm_handle_does_not_fabricate_recovery',lambda:api('/alarms/'+alarm['id']+'/handle','POST',{'projectId':'demo','version':alarm['version'],'status':'RESOLVED','note':'已登记后续核查'}))
    assert api('/alarms/'+alarm['id']+'?projectId=demo')['observedRecovery']==False
    monitor=api('/assets/'+server['id']+'/monitor?projectId=demo');assert monitor['status']=='ALERT' and monitor['mode']=='SIMULATED'
    notices=check('per_recipient_notification_records',lambda:api('/notifications?projectId=demo'))
    assert any(n['channel']=='EMAIL' and n['status']=='NOT_CONFIGURED' for n in notices)
    notice=next(n for n in notices if n['alarmId']==alarm['id'] and n['channel']=='IN_APP')
    check('notification_read_is_separate_from_alarm_recovery',lambda:api('/notifications/'+notice['id']+'/read','POST',{'projectId':'demo'}))
    event['externalEventId']='recovery-'+RUN;event['metrics']['CPU']=10;event['observedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z');api('/observations','POST',event)
    assert api('/alarms/'+alarm['id']+'?projectId=demo')['observedRecovery']==True
    H=server['id']
    def task(kind,typ,config,production=False):
        config=dict(config);config['targetIds']=[H]
        if production:config['production']=True
        t=api('/tasks','POST',{'projectId':'demo','name':kind+'-'+RUN,'kind':kind,'type':typ,'mode':'SIMULATED','config':config})
        v=api('/tasks/'+t['id']+'/validate','POST',{'version':t['version']});assert v['valid'],v
        return t
    def execute(t):
        key=str(uuid.uuid4());run=api('/tasks/'+t['id']+'/execute','POST',{'version':t['version'],'idempotencyKey':key});assert api('/tasks/'+t['id']+'/execute','POST',{'version':t['version'],'idempotencyKey':key})['id']==run['id']
        for _ in range(50):
            run=api('/runs/'+run['id'])
            if run['status'] not in ('QUEUED','RUNNING'):break
            time.sleep(.3)
        assert run['status']=='SUCCEEDED',(run['status'],api('/runs/'+run['id']+'/logs'))
        assert run['mode']=='SIMULATED' and all(x['status']=='SUCCEEDED' for x in run['targets'])
        return run
    cluster=task('CLUSTER','PVE',{'physicalAssetIds':[H],'platformVersion':'8','roles':{H:'master'},'network':'192.0.2.0/24','storage':'local','credentialRef':'env:DEMO_PVE'})
    check('durable_cluster_run_and_idempotent_receipt',lambda:execute(cluster))
    vm=task('VM','GENERAL',{'clusterId':cluster['id'],'hostId':H,'template':'ubuntu-24.04','cpu':2,'memoryGb':4,'diskGb':20,'storagePool':'local','networkBridge':'vmbr0','ip':'192.0.2.40','credentialRef':'env:DEMO_PVE'})
    check('vm_host_capacity_and_durable_run',lambda:execute(vm))
    env=task('ENVIRONMENT','MICROSERVICE_FRAMEWORK',{'dependsOnTaskIds':[cluster['id']],'version':'1.0','namespace':'demo','storage':'local','domain':'demo.invalid'})
    check('environment_dependency_and_run',lambda:execute(env))
    deploy=task('DEPLOYMENT','APPLICATION',{'artifact':'registry.invalid/demo/app:1.0','artifactDigest':'sha256:'+'a'*64,'environment':env['id'],'namespace':'demo','strategy':'ROLLING','healthCheck':'/health','timeoutSeconds':60,'failurePolicy':'STOP'},True)
    check('production_execution_requires_confirmation',lambda:api('/tasks/'+deploy['id']+'/execute','POST',{'version':deploy['version'],'idempotencyKey':str(uuid.uuid4())},expected=(409,422)))
    api('/tasks/'+deploy['id']+'/confirm','POST',{'version':deploy['version']})
    deploymentRun=check('frozen_production_plan_simulated_execution',lambda:execute(deploy))
    dist=task('DISTRIBUTION','FLATPAK',{'dependsOnTaskIds':[deploy['id']],'app':'cn.demo.App','artifact':'registry.invalid/demo/app.flatpak','version':'1.0','architecture':'x86_64','runtime':'org.freedesktop.Platform//24.08','repository':'https://repo.invalid/demo'})
    check('distribution_dependency_and_run',lambda:execute(dist))
    real=api('/tasks','POST',{'projectId':'sandbox','name':'未接入-'+RUN,'kind':'CLUSTER','type':'PVE','mode':'REAL','config':{'targetIds':['not-probed']}})
    check('real_execution_never_reports_success_without_adapter',lambda:api('/tasks/'+real['id']+'/execute','POST',{'version':real['version'],'idempotencyKey':str(uuid.uuid4())},expected=(503,)))
    # Newer features are exercised against the same persisted, authenticated service.
    sorted_page=check('database_filter_sort_and_pagination',lambda:api('/assets?projectId=demo&page=1&pageSize=2&sortBy=name&sortOrder=asc'))
    assert len(sorted_page['items'])==2 and sorted_page['total']>=2
    assert [x['name'] for x in sorted_page['items']]==sorted([x['name'] for x in sorted_page['items']])
    check('invalid_sort_rejected',lambda:api('/assets?projectId=demo&sortBy=arbitrary',expected=(400,)))
    receipt=check('notification_test_persists_receipts',lambda:api('/alarm-rules/'+rule['id']+'/test','POST',{'projectId':'demo'}))
    assert any(x['channel']=='IN_APP' and x['notificationId'] for x in receipt['receipts'])
    for kind in ('DISCOVERY','SYNC'):
        config={'connectorId':'fixture','networkScope':'demo-lan'} if kind=='DISCOVERY' else {'connectorId':'fixture','assetIds':[H]}
        plan=api('/collection-schedules','POST',{'projectId':'demo','name':kind+'-'+RUN,'kind':kind,'mode':'SIMULATED','enabled':False,'intervalMinutes':60,'timezone':'Asia/Shanghai','serviceSubject':'asset-scheduler-demo','config':config})
        assert plan['enabled']==False
        cr=api('/collection-schedules/'+plan['id']+'/execute','POST',{'version':plan['version']})
        for _ in range(50):
            cr=next(x for x in api('/collection-runs?projectId=demo') if x['id']==cr['id'])
            if cr['status'] not in ('QUEUED','RUNNING'):break
            time.sleep(.3)
        assert cr['status']==('PREVIEW_READY' if kind=='SYNC' else 'SUCCEEDED'),cr
        RESULT.append({'name':'durable_scoped_'+kind.lower()+'_collection','status':'PASS'})
        if kind=='SYNC':
            assert cr['previewId']
            api('/assets/sync/confirm','POST',{'projectId':'demo','previewId':cr['previewId']})
    extra=api('/assets/sync/preview','POST',{'projectId':'demo','mode':'SIMULATED','connectorId':'fixture','rows':[{'name':'额外模拟节点-'+RUN,'type':'SERVER','mode':'SIMULATED','connectorId':'fixture','externalId':'fixture-extra-'+RUN,'technical':{'cpuCores':64,'memoryGb':128,'diskGb':2000}}]})
    other=api('/assets/sync/confirm','POST',{'projectId':'demo','previewId':extra['previewId']})['items'][0]
    api('/assets/'+other['id']+'/admit','POST',{'projectId':'demo','version':other['version']})
    partial=api('/tasks','POST',{'projectId':'demo','name':'部分失败-'+RUN,'kind':'CLUSTER','type':'PVE','mode':'SIMULATED','config':{'targetIds':[H,other['id']],'physicalAssetIds':[H,other['id']],'platformVersion':'8','roles':{H:'master',other['id']:'worker'},'network':'192.0.2.0/24','storage':'local','credentialRef':'env:DEMO_PVE','simulationFailureTargetIds':[max(H,other['id'])]}})
    pr=api('/tasks/'+partial['id']+'/execute','POST',{'version':partial['version'],'idempotencyKey':str(uuid.uuid4())})
    for _ in range(50):
        pr=api('/runs/'+pr['id'])
        if pr['status'] not in ('QUEUED','RUNNING'):break
        time.sleep(.3)
    assert pr['status']=='PARTIAL_FAILED' and {t['status'] for t in pr['targets']}=={'SUCCEEDED','FAILED'},pr
    RESULT.append({'name':'partial_failure_preserves_target_results','status':'PASS'})
    check('dashboard_aggregates_persisted_records',lambda:api('/dashboard?projectId=demo'))
    check('audit_trail_available',lambda:api('/audit?projectId=demo'))
    openapi=check('openapi_contract_generated',lambda:api('/openapi'));Path('docs/openapi.json').write_text(json.dumps(openapi,ensure_ascii=False,indent=2))
    check('mcp_requires_authentication',lambda:call('/mcp','POST',{'jsonrpc':'2.0','id':1,'method':'initialize','params':{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'assets-test','version':'1'}}},expected=(401,),headers={'Accept':'application/json, text/event-stream'}))

if __name__=='__main__':
    try:main()
    finally:
        Path('docs/test-results').mkdir(parents=True,exist_ok=True)
        Path('docs/test-results/integration.json').write_text(json.dumps({'run':RUN,'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'results':RESULT},ensure_ascii=False,indent=2))
