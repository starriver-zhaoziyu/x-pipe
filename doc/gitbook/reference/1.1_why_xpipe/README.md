## 如何判断业务是否需要接入XPipe？

假设IDC1挂了，进行DR切换，业务切换到IDC2

- 在IDC2，如果没有Redis，业务依然能够正常运行，则**不需要接入XPipe**
- 如果需要Redis，但是能接受Redis DBA**小时级时间**重建**新的****空的**Redis缓存，则**不需要接入XPipe**
- 如果需要Redis，希望Redis**立即可用**，并且IDC1内的数据仍然**存在**，**则需要接入XPipe**

## 接入XPipe资源消耗以及可能问题

- 资源消耗增加1.2倍

  假设目前场景：

  业务Redis集群部署在IDC1，需要在IDC2建立DR；在IDC1内有有两个Redis实例，一个master、一个slave

  - IDC2内需要增加和IDC1内相同数目的Redis实例 ： +2
  - 需要增加4个keeper复制节点，但是keeper资源消耗为约为Redis的0.1倍： +0.4
  - 所有资源变化：2->4.4，增加1.2倍

- XPipe异步复制

  - 极端情况下DR切换时会有数据丢失，可能丢失数据的场景：
    - 业务写入master成功，写入的数据没有复制出去master就挂掉
    - 机房之间网络异常，数据无法跨机房传输
  - XPipe保证最终一致性，无法提供强一致性保证
    - 玩乐部门case举例
      - 问题描述
        - 玩乐BU一个业务系统的Redis集群有三个分片(shard/group)
        - 要求三个分片同时能够读到特定业务数据，才能提供服务
        - DR切换后，由于某个分片数据不齐(可能切换过程中写入异常，无重试)，导致切换后部分景点无法提供服务
      - 解决
        - 需要保证强一致数据写入一个Redis分片

- 缓存补偿

  - 如果业务需要确保

    **缓存与数据库数据强一致**，请关注此节

    - XPipe切换流程会先关闭master的写入，然后再执行切换，在切换过程中，业务会有短暂的写入报错（根部不同客户端版本）
    - 在切换期间，如果写入失败的数据**没有重试或者补偿机制**，可能会导致**缓存与数据库数据不一致**

  - 解决方案

    - 升级CRedis客户端版本(降低切换过程中写入失败时间)
    - 建立缓存写入失败的**重试和补偿**机制

- 极端情况下，多个机房同时挂掉，数据仍然可能丢失

## 各方案对比

|                              | 无XPipe，DBA不重建缓存 | 无XPipe，DBA重建缓存 | XPipe    |
| :--------------------------- | :--------------------- | :------------------- | :------- |
| DR切换时间                   | 无                     | 小时级               | 3秒      |
| DR后，新机房是否有原机房数据 | 无                     | 无                   | 有       |
| 需要资源                     | 单DC资源               | 单DC资源             | 资源加倍 |