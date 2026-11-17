#!/usr/bin/env python3
import random,sys
random.seed(7)
rows=[]
for _ in range(600):
    n=random.randint(1,9); idx=random.sample(range(64),n)
    bw=random.choice([100,180,256,65.5]); bd=random.choice([100,180,256,70.25]); c=random.choice([0,1,2,2.5])
    parts=" ".join(f"{random.choice([5,12.5,20,33.3,60,90,101,150])} {random.choice([5,10,24.75,34,66,80,181])} {i}" for i in idx)
    rows.append(f"{bw} {bd} {c} {n} {parts}")
open(sys.argv[1],"w").write("\n".join(rows)+"\n")
