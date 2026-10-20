import csv, sys, re, statistics, os
def pct(v,p):
    v=sorted(v); 
    if not v: return float('nan')
    k=(len(v)-1)*p/100; f=int(k); c=min(f+1,len(v)-1); return v[f]+(v[c]-v[f])*(k-f)
for run in sys.argv[1:]:
    d=os.path.join(os.path.dirname(os.path.abspath(__file__)),'results',run)
    print('=====',run, open(d+'/jvm-flags.txt').read().strip())
    print(open(d+'/startup.txt').read().strip())
    rows=list(csv.DictReader(open(d+'/requests.csv')))
    groups={}
    for r in rows: groups.setdefault((r['phase'],r['label']),[]).append(r)
    print(f"{'phase':7} {'label':28} {'n':>6} {'p50':>7} {'p95':>7} {'p99':>7} {'max':>7} {'statuses':20} bytes/req")
    for (ph,l),g in sorted(groups.items()):
        ms=[float(r['ms']) for r in g]; st={}
        for r in g: st[r['status']]=st.get(r['status'],0)+1
        print(f"{ph:7} {l:28} {len(g):6} {pct(ms,50):7.2f} {pct(ms,95):7.2f} {pct(ms,99):7.2f} {max(ms):7.2f} {str(st):20} {statistics.mean(int(r['bytes']) for r in g):.0f}")
    work=[r for r in rows if r['phase']=='work']
    if work:
        span=(int(work[-1]['epoch_ms'])-int(work[0]['epoch_ms']))/1000
        authed=[r for r in work if r['status'] in('200',) and r['label'] not in('page_login','signin_options','signin_assertion','logout')]
        print(f"work span {span:.0f}s requests {len(work)} ({len(work)/span*3600:.0f}/h) authed-checked {len(authed)} ({len(authed)/span*3600:.0f}/h) response bytes {sum(int(r['bytes']) for r in work)/span*3600/1e6:.2f} MB/h")
        errs=[r for r in work if r['status'] not in ('200','302')]
        print('non-2xx in work:', len(errs), set((r['label'],r['status']) for r in errs))
    s=[l.split() for l in open(d+'/samples.txt') if len(l.split())>=4]
    rss=[int(x[1])/1024 for x in s]
    if rss: print(f"RSS MiB: first {rss[0]:.0f} min {min(rss):.0f} median {statistics.median(rss):.0f} max {max(rss):.0f} last {rss[-1]:.0f} samples {len(rss)}")
    # rss trend: first vs last 10%
    if len(rss)>20:
        n=len(rss)//10; print(f"RSS trend: first-10% median {statistics.median(rss[:n]):.0f}  last-10% median {statistics.median(rss[-n:]):.0f}")
    def t2s(t):
        p=t.split(':'); return float(p[-1])+60*float(p[-2])+(3600*float(p[-3]) if len(p)>2 else 0)
    if s: print(f"CPU time total {t2s(s[-1][3]):.1f}s over {int(s[-1][0])-int(s[0][0])}s wall -> avg {t2s(s[-1][3])/(int(s[-1][0])-int(s[0][0]))*100:.2f}% of one core")
    for f in ('heap-after-start','heap-idle','heap-end'):
        t=open(d+f'/{f}.txt').read()
        m=re.findall(r'(\w+ generation|def new generation|tenured generation|garbage-first heap|Metaspace)\s+(?:total|used)?[^\n]*?(?:total|capacity)\s*(\d+)K, (?:used|committed)\s*(\d+)K',t)
        print(f, ' | '.join(' '.join(x for x in l.split()) for l in t.splitlines()[1:6]))
    for f in ('nmt-after-start','nmt-end'):
        t=open(d+f'/{f}.txt').read()
        tot=re.search(r'Total: reserved=(\d+)KB, committed=(\d+)KB',t)
        parts=re.findall(r'-\s+(\w[\w ]*?) \(reserved=\d+KB, committed=(\d+)KB',t)
        print(f, 'committed MiB', int(tot.group(2))//1024 if tot else '?', ' '.join(f"{n.strip()}={int(c)//1024}" for n,c in parts if int(c)>1024))
    gc=open(d+'/gc.log').read()
    pauses=[float(x) for x in re.findall(r'Pause \w+[^\n]*?(\d+\.\d+)ms',gc)]
    if pauses: print(f"GC pauses: n={len(pauses)} p50={pct(pauses,50):.2f}ms max={max(pauses):.2f}ms total={sum(pauses):.0f}ms")
    print('driver:', open(d+'/driver.log').read().strip().replace('\n',' | ')[:300])
