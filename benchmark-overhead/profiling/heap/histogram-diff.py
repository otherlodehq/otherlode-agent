# Prints the classes whose live bytes differ most between two `jcmd GC.class_histogram` outputs.
# usage: histogram-diff.py <before> <after> [rows]
import re,sys
def h(p):
    d={}
    for line in open(p):
        m=re.match(r'\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)',line)
        if m: 
            k=m.group(3); n,b=d.get(k,(0,0)); d[k]=(n+int(m.group(1)),b+int(m.group(2)))
    return d
a=h(sys.argv[1]); b=h(sys.argv[2]); N=int(sys.argv[3]) if len(sys.argv)>3 else 40
rows=sorted(set(a)|set(b), key=lambda k:-(b.get(k,(0,0))[1]-a.get(k,(0,0))[1]))
tot=sum(v[1] for v in b.values())-sum(v[1] for v in a.values())
print(f"total diff {tot/1048576:+.2f} MB")
for k in rows[:N]:
    da=a.get(k,(0,0)); db=b.get(k,(0,0))
    print(f"{(db[1]-da[1])/1024:+10.1f} KB {db[0]-da[0]:+9d}  {k}")
ag=[(k,v) for k,v in b.items() if 'otherlode' in k]
print("otherlode-typed total in B: %.2f MB"%(sum(v[1] for k,v in ag)/1048576))
