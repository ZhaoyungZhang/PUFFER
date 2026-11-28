## Install OceanBase

There are several ways to install OceanBase. A local single-node cluster is sufficient for SQLancer development.

### Install OBD by using RPM packages (only for CentOS 7 or later)

```shell
sudo yum install -y yum-utils
sudo yum-config-manager --add-repo https://mirrors.aliyun.com/oceanbase/OceanBase.repo
sudo yum install -y ob-deploy
source /etc/profile.d/obd.sh
```

### Start an OceanBase cluster

```shell
git clone https://github.com/oceanbase/obdeploy.git
cd obdeploy 
sudo obd cluster deploy c1 -c ./example/mini-local-example.yaml -A
sudo obd cluster start c1
```

After you install OBD, you can run these commands as the root user to start a local single-node OceanBase cluster.
Before you run the commands, make sure that these conditions are met:

- You have logged on as the root user.
- Ports `2881` and `2882` are available.
- Your server has at least 8 GB of memory.
- Your server has at least 2 CPU cores.

> **NOTE:** If the preceding conditions are not met, see [OceanBase Deployer](https://github.com/oceanbase/obdeploy/blob/master/README.md).

> **NOTE:** Do not use the `sys` tenant for SQLancer. Create a separate MySQL-mode tenant and a non-root test user.

### Create a test user

```shell
mysql -h127.1 -uroot@test -P2881 -Doceanbase -A -e"create user sqlancer identified by 'sqlancer';grant all on *.* to sqlancer;"
```

## OceanBase CE 4.5 support

`PERFORMANCE_HINT` is supported in OceanBase MySQL mode. A Q -> Q′ batch selects either CE 4.5 optimizer hints or
WeTune static rewrites; a selected rewrite batch checks every returned rewrite, up to 16, and does not substitute a
HINT batch when no rewrite is returned. The CE 4.5 HINT families are:

- access: `FULL(table)` and `INDEX(table index)`;
- join/order: `LEADING(...)`, `USE_NL(...)`, `USE_HASH(...)`, and `ORDERED`;
- aggregation: `USE_HASH_AGGREGATION` and `NO_USE_HASH_AGGREGATION`.

The oracle reads `EXPLAIN FORMAT=JSON`'s `EST.TIME(us)` field, including JDBC result sets that return the JSON one line per row. It verifies HINT acceptance with `EXPLAIN EXTENDED`'s `Used Hint` section, refreshes statistics with `ANALYZE TABLE`, then re-explains every candidate before measuring the base and every surviving Q′. Deferred result validation compares complete row multisets, including every column and duplicate count. Results are classified in the A–I cost/runtime grid and written with incremental telemetry; A, D, and G are formal outcomes while the other enabled cells are observations. CE 4.5 does not accept `EXPLAIN ANALYZE` in this setup, so the optional plan-detail stage automatically falls back to `EXPLAIN EXTENDED` for HINTs.

QPG is also supported: OceanBase JSON plans are normalized into stable operator/name sequences, and `PERFORMANCE_HINT` exposes its last base query to the QPG driver.

For the isolated CE 4.5 development deployment, use:

```shell
scripts/run_oceanbase_4_5_sqlancer.sh build
NUM_QUERIES=100 scripts/run_oceanbase_4_5_sqlancer.sh smoke
NUM_QUERIES=100 scripts/run_oceanbase_4_5_sqlancer.sh qpg
scripts/run_oceanbase_4_5_sqlancer.sh --config configs/oceanbase-4.5.0-qpg-perfhint-wetune-1w.properties qpg-campaign --detach
```

Set `OB45_CREDENTIALS_FILE` (or `HOST`, `PORT`, `USERNAME`, and `PASSWORD`) to point at your deployment; the script checks that the server reports CE 4.5. The supplied one-week configuration uses the built Java 17 worker at `tools/wetune-static-worker/build/install/wetune-static-worker/bin/wetune-static-worker`; the runner checks it before starting. Pass extra OceanBase options after `smoke`, `qpg`, `campaign`, or `qpg-campaign`, for example `--perf-min-rows-per-table 2000`.

For other installation methods, see [OceanBase Deployer](https://github.com/oceanbase/obdeploy/blob/master/README.md).
