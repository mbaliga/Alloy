#!/usr/bin/env python3
"""Deterministic G-code corpus for Java<->Swift GcodeSafetyValidator parity.
Writes one case per line: strict(0/1) <TAB> hex(utf-8 bytes of gcode)."""
import random, sys
random.seed(20261117)
base = ["G28","G90","G91","M82","M83","M104 S0","M104 S200","M140 S0","M140 S60","G92 E0",
 "G1 X10 Y10 E0.4","G1 X{} Y{} E{}","G0 X{} Y{}","G1 Z{}","G2 X{} Y{} I{} J{} E0.3","G3 X{} Y{} I{} J{} E0.3",
 "G2 X{} Y{} R{}","; G90","(M83) G1 X1","N5 G1 X3 Y4 E1*55","g1 x3 y4 e1","G1 X5. Y5. E1","G1 X1e1 Y.5 E+.2",
 "G1X5 Y5 E1","M104 S 0","M104S0","  G1   X7   Y8   E.5  ","G28 X","G28 Z","G92 X5 Y5","G1 X-3 Y-3 E1","G1 X1e Y2 E1",
 "G1 F3000","T0","M104 S0.00001","M104 S1e-9","G3 X1 Y1 I0 J0","G1 X999999999 Y1 E1","G1 X5 E1 ;X999"]
def num(): return random.choice(["0","5","10.5","90","170","179.9","180.4","180.6","200","-0.4","-0.6","1e1","0.25",".5","3."])
def line():
    t=random.choice(base)
    while "{}" in t: t=t.replace("{}",num(),1)
    return t
out=[]
for k in range(1500):
    n=random.randint(1,14)
    ls=[line() for _ in range(n)]
    if random.random()<0.6: ls=["G28","G90","M83"]+ls
    if random.random()<0.6: ls+=["M104 S0","M140 S0"]
    sep=random.choice(["\n","\n","\r\n"])
    g=sep.join(ls)+random.choice(["","\n"])
    out.append((random.randint(0,1),g))
for strict in (0,1):
    out.append((strict,"G28\nG90\nM83\nG1 X10 Y90\nG3 X90 Y90 I40 J0 E0.4\nM104 S0\nM140 S0\n"))
    out.append((strict,"G28\nG90\nM83\nG1 X10 Y110\nG2 X170 Y110 I80 J0 E0.4\nM104 S0\nM140 S0\n"))
    out.append((strict,"N1 G90*11\nM83\nG1 X1 Y1 E0.25\nM104 S0\n"))
    out.append((strict,"\n\n  \n"))
with open(sys.argv[1],"w") as f:
    for s,g in out: f.write(f"{s}\t{g.encode().hex()}\n")
print(len(out),"cases")
