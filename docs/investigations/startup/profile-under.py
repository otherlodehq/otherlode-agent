# Breaks down the main thread's time under one frame (for example ClassFileLocator) in a collapsed
# profile, by the frames below it.
# usage: profile-under.py <collapsed profile> <frame substring> [depth] [exclude substring]
import sys, collections
f, needle = sys.argv[1], sys.argv[2]; depth=int(sys.argv[3]) if len(sys.argv)>3 else 6
excl = sys.argv[4] if len(sys.argv)>4 else None
c=collections.Counter(); leaf=collections.Counter(); tot=0
for line in open(f):
    stack,_,n=line.rstrip('\n').rpartition(' '); n=int(n)
    if not (stack.startswith('[DestroyJavaVM') or stack.startswith('[main')): continue
    if needle not in stack: continue
    if excl and excl in stack: continue
    tot+=n
    fr=stack.split(';'); i=max(k for k,x in enumerate(fr) if needle in x)
    c[' > '.join(x.split('/')[-1] for x in fr[i+1:i+1+depth])]+=n
    # classify by deepest interesting frame
    s=stack[stack.index(needle):]
    for key in ['ZipContent','NestedJar','jar/JarFile','ZipFile','getResource','ClassFileLocator$ForClassLoader','TypePool$Default.parse','ClassReader','Advice','TypeWriter','asm','TypePool','Matcher']:
        if key in s: leaf[key]+=n; break
    else: leaf['other']+=n
print('total',tot)
for k,n in c.most_common(25): print(f'{n:6d} {k}')
print('--- contains (first match in priority order)')
for k,n in leaf.most_common(): print(f'{n:6d} {k}')
