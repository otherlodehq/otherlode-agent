# Prints the native memory areas that differ between `jcmd VM.native_memory summary` outputs.
# usage: nmt-diff.py <baseline> <other>...
import re,sys
def nmt(p):
    d={}; cur=None
    for line in open(p):
        m=re.match(r'-\s+(.+?) \(reserved=(\d+)KB, committed=(\d+)KB',line)
        if m: cur=m.group(1).strip(); d[cur]=int(m.group(3)); continue
        m=re.match(r'Total: reserved=(\d+)KB, committed=(\d+)KB',line)
        if m: d['TOTAL']=int(m.group(2))
        m=re.search(r'\(classes #(\d+)\)',line)
        if m: d['#classes']=int(m.group(1))*1024
        m=re.search(r'\(threads #(\d+)\)',line)
        if m: d['#threads']=int(m.group(1))*1024
        m=re.search(r'\(malloc=(\d+)KB',line)
        if m and cur: d[cur+' malloc']=int(m.group(1))
    return d
fs=sys.argv[1:]; ds=[nmt(f) for f in fs]
keys=sorted(set().union(*ds), key=lambda k:-(ds[-1].get(k,0)-ds[0].get(k,0)))
for k in keys:
    v=[d.get(k,0)/1024 for d in ds]
    if max(abs(x-v[0]) for x in v)<0.3: continue
    print(f"{k:28}"+"".join(f"{x:9.1f}" for x in v)+"".join(f"{x-v[0]:+9.1f}" for x in v[1:]))
