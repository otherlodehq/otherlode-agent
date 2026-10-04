# Buckets a collapsed async-profiler wall profile of PetClinic startup by what the main thread was
# doing: premain, each part of the transform pipeline, or the application.
# usage: profile-buckets.py <collapsed profile> [main|<thread name prefix>]
# Profile with async-profiler 4.x: -agentpath:<libasyncProfiler>=start,event=wall,interval=1ms,file=out.txt,collapsed
import sys, collections, re
f = sys.argv[1]; mode = sys.argv[2] if len(sys.argv)>2 else 'main'
threads = collections.Counter(); main_total=0; buckets=collections.Counter(); agentframes=collections.Counter()
for line in open(f):
    stack, _, n = line.rstrip('\n').rpartition(' ')
    n = int(n); frames = stack.split(';'); th = frames[0]
    tname = re.sub(r' tid=\d+\]','',th)[1:]
    threads[tname]+=n
    if mode=='main':
        if not (th.startswith('[DestroyJavaVM') or th.startswith('[main')): continue
        if not ('JarLauncher.main' in stack or 'loadClassAndCallPremain' in stack): continue
    else:
        if not tname.startswith(mode): continue
    main_total += n
    if 'loadClassAndCallPremain' in stack: b='premain'
    elif 'TransformerManager.transform' in stack:
        i = stack.index('TransformerManager.transform'); sub = stack[i:]
        if 'ClassBytesCapture' in sub: b='xform:ClassBytesCapture'
        elif 'jaxrs' in sub.lower(): b='xform:endpoint JaxRs matcher'
        elif '/endpoints/' in sub: b='xform:endpoint other'
        elif 'OtherlodeInstrumentation.instrument' in sub or 'OtherlodeInstrumentation$buildTransformer' in sub and 'transform' in sub: b='xform:method-tier instrument()'
        elif 'TypeMatchPolicy' in sub or 'OtherlodeInstrumentation' in sub: b='xform:method-tier other(matcher/listener)'
        else: b='xform:bytebuddy-unattributed'
    elif 'dev/otherlode' in stack: b='agent other'
    else: b='app'
    buckets[b]+=n
print('total', main_total)
for b,n in buckets.most_common(): print(f'{n:7d} {100*n/max(main_total,1):5.1f}% {b}')
if mode!='main' or len(sys.argv)>3:
    pass
print('threads:', [(t,n) for t,n in threads.most_common(15)])
