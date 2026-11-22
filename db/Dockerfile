# syntax=docker/dockerfile:1
# PostgreSQL for Docker Compose, with the pgvector extension (MVP 4 semantic search).
#
# Built on the same postgres:16-alpine image the stack used before MVP 4, so an existing pgdata volume
# keeps working unchanged: same major version, same C library (musl) and therefore the same collation
# behaviour. (Switching to the Debian-based pgvector/pgvector image would change the C library under an
# existing data directory, which can silently invalidate text indexes.)
#
# pgvector is compiled from a pinned, checksum-verified source release. OPTFLAGS="" builds portable code
# (no -march=native); with_llvm=no skips the optional JIT bitcode, so clang/llvm are not needed.
FROM postgres:16-alpine

ARG PGVECTOR_VERSION=0.8.7
ARG PGVECTOR_SHA256=cac0b10c360f05b2d521200105ba3697e773d4cd3731f5a915a7e37ebe0bea85

RUN set -eux \
 && apk add --no-cache --virtual .pgvector-build build-base curl \
 && curl -fsSL -o /tmp/pgvector.tar.gz "https://github.com/pgvector/pgvector/archive/refs/tags/v${PGVECTOR_VERSION}.tar.gz" \
 && echo "${PGVECTOR_SHA256}  /tmp/pgvector.tar.gz" | sha256sum -c - \
 && mkdir -p /tmp/pgvector \
 && tar -xzf /tmp/pgvector.tar.gz -C /tmp/pgvector --strip-components=1 \
 && make -C /tmp/pgvector OPTFLAGS="" with_llvm=no \
 && make -C /tmp/pgvector install with_llvm=no \
 && rm -rf /tmp/pgvector /tmp/pgvector.tar.gz \
 && apk del .pgvector-build \
 && test -f "$(pg_config --sharedir)/extension/vector.control"
