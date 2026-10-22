import {test,expect} from '../../frontend/e2e';
import path from 'node:path';

const username=process.env.E2E_USERNAME||'admin';
const password=process.env.E2E_PASSWORD;

test('normal OIDC login, seven routes, persisted asset and no page errors',async({page},testInfo)=>{
 if(!password)throw new Error('Set E2E_PASSWORD to a local development realm test password. No token or authentication bypass is used.');
 const pageErrors:string[]=[];page.on('pageerror',error=>pageErrors.push(error.message));
 await page.goto('/');
 await page.getByRole('button',{name:'登录工作台'}).click();
 await page.getByLabel('Username',{exact:true}).fill(username);
 await page.getByLabel('Password',{exact:true}).fill(password);
 await page.getByRole('button',{name:'Sign In',exact:true}).click();
 await expect(page.getByRole('heading',{name:'资产总览',exact:true})).toBeVisible();
 await expect(page.getByText('项目数据访问受控',{exact:true})).toBeVisible();
 const tagState=await page.locator('.el-tag').allTextContents();
 console.log('Rendered state tags:',JSON.stringify(tagState.slice(0,8)));
 await expect(page.locator('.el-tag').first()).toBeVisible();
 await page.screenshot({path:path.resolve(__dirname,'../../docs/screenshots/overview.jpg'),fullPage:true,animations:"disabled"});
 for(const label of ['资产总览','资产管理','计算资源','环境部署','应用部署','应用分发','告警管理']){
  await page.locator('.sidebar').getByRole('link',{name:label,exact:true}).click();
  await expect(page.getByRole('heading',{name:label,exact:true})).toBeVisible();
  await expect(page.getByText('处理失败，请通过请求编号查询日志',{exact:false})).toHaveCount(0);
 }
 await page.locator('.sidebar').getByRole('link',{name:'资产管理',exact:true}).click();
 await page.getByRole('button',{name:'手动新增',exact:true}).click();
 const drawer=page.getByRole('dialog',{name:'手动新增资产',exact:true});
 const assetName=`浏览器回归资产-${Date.now()}`;
 await drawer.getByLabel('资产名称 *',{exact:true}).fill(assetName);
 await drawer.getByLabel('资产类型 *',{exact:true}).press('Enter');
 await page.getByRole('option',{name:'软件资产',exact:true}).click();
 const saveReceipt=page.waitForResponse(response=>response.url().split('?')[0].endsWith('/api/v1/assets')&&response.request().method()==='POST');
 await drawer.getByRole('button',{name:'保存资产',exact:true}).click();
 const receipt=await saveReceipt;
 expect(receipt.status()).toBe(201);
 const saved=await receipt.json();expect(saved.id).toBeTruthy();expect(saved.name).toBe(assetName);expect(saved.mode).toBe('REAL');
 await expect(drawer).toBeHidden();
 await expect(page.getByRole('button',{name:assetName,exact:true})).toBeVisible();
 await page.reload();
 await expect(page.getByRole('button',{name:assetName,exact:true})).toBeVisible();
 await expect(page.getByText('真实执行',{exact:true}).first()).toBeVisible();
 await expect(page.getByText('模拟执行',{exact:true}).first()).toBeVisible();
 await page.screenshot({path:path.resolve(__dirname,'../../docs/screenshots/assets.jpg'),fullPage:true,animations:"disabled"});
 expect(pageErrors,'Vue/runtime page errors').toEqual([]);
 await testInfo.attach('saved-asset-receipt',{body:JSON.stringify({id:saved.id,name:saved.name,mode:saved.mode}),contentType:'application/json'});
});
