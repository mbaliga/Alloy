# Alloy Runner image: the Node service (this repository, PolyForm Noncommercial) plus gmsh and Elmer as separate programs it starts.
# gmsh and Elmer are GPL/LGPL and are installed from their distributors' packages; the service never links them (runner/THIRD_PARTY.md).
#
# NOT BUILT BY THE AUTHOR: the sandbox this was written in has no Docker daemon, so this file has not been run. The pieces it relies on are
# plain: Ubuntu's gmsh package (the service's gmsh step was run against Ubuntu's gmsh 4.12 outside the container), the Elmer CSC PPA (the
# install route the Elmer project documents for Ubuntu), and a Node binary copied from the official Node image. After building,
# GET /v1/health (with the token) lists what is installed: the `elmer` adapter must read ok before a job can use it, and the package
# name of the Elmer install (elmerfem-csc) is the first thing to check if it does not.
#
#   docker build -t alloy-runner -f runner/Dockerfile runner
#   docker run --rm -p 127.0.0.1:8787:8787 -e ALLOY_RUNNER_TOKEN="$(openssl rand -hex 24)" -e ALLOY_RUNNER_ORIGINS=https://your-forge-origin alloy-runner
FROM node:22-bookworm-slim AS node

FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates software-properties-common gmsh \
 && add-apt-repository -y ppa:elmer-csc-ubuntu/elmer-csc-ppa \
 && apt-get update \
 && apt-get install -y --no-install-recommends elmerfem-csc \
 && apt-get purge -y software-properties-common && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node

RUN useradd --system --create-home --home-dir /home/runner --shell /usr/sbin/nologin runner && mkdir /data && chown runner /data
WORKDIR /app
COPY package.json ./
COPY bin ./bin
COPY src ./src
USER runner

ENV ALLOY_RUNNER_HOST=0.0.0.0 \
    ALLOY_RUNNER_PORT=8787 \
    ALLOY_RUNNER_DATA=/data \
    ALLOY_RUNNER_GMSH=gmsh \
    ALLOY_RUNNER_ELMER=ElmerSolver
# ALLOY_RUNNER_TOKEN has no default: the service refuses to start without one.
EXPOSE 8787
VOLUME ["/data"]
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD ["node", "-e", "fetch('http://127.0.0.1:'+(process.env.ALLOY_RUNNER_PORT||8787)+'/v1/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
CMD ["node", "bin/alloy-runner.mjs"]
