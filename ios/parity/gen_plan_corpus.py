#!/usr/bin/env python3
import random,sys
random.seed(7)
rows=[]
for _ in range(600):
    n=random.randint(1,9); idx=random.sample(range(64),n)
    bw=random.choice([100,180,256,65.5]); bd=random.choice([100,180,256,70.25]); c=random.choice([0,1,2,2.5])
    parts=" ".join(f"{random.choice([5,12.5,20,33.3,60,90,101,150])} {random.choice([5,10,24.75,34,66,80,181])} {i}" for i in idx)
    rows.append(f"{bw} {bd} {c} {n} {parts}")
# Boundary cases around the planner's EPSILON (0.0001f): sizes at/just inside/just outside the bed,
# and width/depth differences straddling the "square part" test. Appended after the random rows so the
# original 600 rows are unchanged.
for d in (0.0, 0.00005, 0.00009, 0.00011, 0.0002, 0.001, 0.0005):
    for cl in (0, 1):
        rows.append(f"180 180 {cl} 1 {180 + d} 20 0")
        rows.append(f"180 180 {cl} 1 20 {180 + d} 0")
        rows.append(f"180 180 {cl} 2 {90 + d} 30 0 {90 - cl + d} 30 1")
        rows.append(f"100 100 {cl} 2 40 {40 + d} 0 {40 + d} 40 1")
        rows.append(f"100 100 {cl} 3 {50 - cl / 2 + d} 10 0 {50 - cl / 2} 10 1 {50 - cl / 2} {10 + d} 2")
open(sys.argv[1],"w").write("\n".join(rows)+"\n")
