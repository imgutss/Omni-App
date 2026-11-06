#!/usr/bin/env python3
"""Fail-closed APK reuse for harness-only reruns; NEVER a release or app build.
The signed parent artifact is usable only when its successful build belongs to this
workflow/repository and every non-harness byte in git matches the parent SHA.
"""
import json,os,re,subprocess,urllib.request

repo=os.environ['GITHUB_REPOSITORY']
run=os.environ['UI_PARENT_RUN']
assert re.fullmatch(r'[0-9]+',run),'Parent run must be a numeric Actions ID'
assert not os.environ.get('RELEASE_TAG'),'Harness-only APK reuse cannot publish a release'
headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'}
def get(path):
    request=urllib.request.Request('https://api.github.com/repos/'+repo+path,headers=headers)
    with urllib.request.urlopen(request,timeout=40)as response:return json.load(response)
parent=get('/actions/runs/'+run)
assert parent['head_repository']['full_name']==repo,'Foreign source repository'
assert parent['workflow_id']==400256906,'Not the signed Omni build workflow'
sha=parent['head_sha'];assert re.fullmatch(r'[0-9a-f]{40}',sha),'Invalid parent source SHA'
jobs=get('/actions/runs/'+run+'/jobs?per_page=100')['jobs']
assert any(j['name']=='build'and j['conclusion']=='success'for j in jobs),'Parent build has not passed'
subprocess.run(['git','fetch','--no-tags','origin',sha,'--depth=1'],check=True)
changed=subprocess.check_output(['git','diff','--name-only',sha,'HEAD'],text=True).splitlines()
allowed={'.github/workflows/omni-build.yml','tools/omni-native-ui-smoke.py','tools/omni-exact-parity.json','tools/verify-omni-ui-smoke-reuse.py'}
assert set(changed)<=allowed,'App/native/resource changes require a full build: '+repr(sorted(set(changed)-allowed))
print('Verified signed parent build',run,'source',sha,'with harness-only differences:',changed)
