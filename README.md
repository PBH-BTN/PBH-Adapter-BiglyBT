# PBH-Adapter-BiglyBT

PeerBanHelper 的 BiglyBT 适配器插件。

> [!NOTE]
> PBH-Adapter-BiglyBT 插件需要 Java 11 或者更高版本的 Java。请确认您的 BiglyBT 正在使用 11 或者更高版本的 Java，否则插件将无法运行

> [!NOTE]
> 你正在使用 Vuze/Azureus 吗？BiglyBT 的插件与 Vuze/Azureus 的并不兼容，在[这里](https://github.com/PBH-BTN/PBH-Adapter-Azureus)可以找到适合 Vuze/Azureus 的适配器。（注：如果您还在使用 Vuze，请考虑升级到 BiglyBT）

## 安装

### 第一步

从 [Github Releases](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/releases) 下载您的 PeerBanHelper 对应版本的 PBH-Adapter-BiglyBT 插件 jar 包（如果没有说明，请下载最新版本）。

### 第二步

打开您的 BiglyBT 下载器，选择 “工具 -> 插件 -> 从文件安装……”

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/68d751f9-2cfa-491c-9462-cf05c0a7961c)

选中刚刚下载的 `.jar` 文件，然后下一步安装。

![image](https://github.com/user-attachments/assets/47964e0e-b053-4fc1-8c11-5509cab788f0)

如果 BiglyBT 询问您为谁安装，请根据自己的需要选择。如无特殊要求，请选择默认的选项。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/527467ab-e92d-41f8-853a-9f86562f096c)

弹出插件安装对话框后，请点击 “安装” 按钮。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/71133241-9d11-444a-a19b-a874a4850d6c)

如果弹出安全警告，请您允许继续安装。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/d918e8ce-7126-4521-8c1b-fb0c4120bb0f)

直到提示安装成功，此时 PeerBanHelper BiglyBT Adapter 适配器的安装过程就完成了。请根据下面的教程继续配置。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/83471d40-f707-431c-8fe7-f05b73de5763)

## 配置

为了安全起见，PBH-Adapter-BiglyBT 将会生成一个随机 Token，你需要获取这个随机 Token 才能在 PeerBanHelper 中连接到您的 BiglyBT 下载器。  
点击 “工具->选项” 打开选项窗口。点击 “插件” 左侧的倒三角标志，展开插件配置菜单。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/bbab5581-6c14-4354-9861-9a71c7107c01)

找到 “PeerBanHelper 适配器 - 配置界面”，点击进入配置页面。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/d0b84c5e-15dc-4403-8caa-f4627cffe3bf)

配置 API 端口号，并记下 Token。新安装默认只监听 `127.0.0.1`，适用于 PeerBanHelper 和 BiglyBT 在同一主机上的部署。跨主机或容器连接时，请把「API 绑定地址」设为 PeerBanHelper 能访问的本机地址，或通过反向代理提供 HTTPS。升级保留现有端口和 Token，旧安装继续沿用原有的监听范围，直到显式修改绑定地址。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/f9eb6630-e5f0-43e9-8ac0-cec162ce3339)

## 连接

打开 PeerBanHelper 的 WebUI，点击添加按钮。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/5ecb7ad9-60f9-4325-bfd8-6ebff47c1875)

选择 “BiglyBT”，并填写刚刚设置的端口号和记下的 Token：

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/0ea581a5-23ff-491c-818b-a43b1bb36d66)

填完应当如下图所示（请根据自己实际情况填写，不要照抄，连不上的）。启用增量封禁后，支持新版协议的 PeerBanHelper 会同步新增和移除的地址，并在插件重启或版本不一致时重新发送完整列表

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/5dde841f-b0dd-4745-a920-f9c8b3f676c9)

点击按钮，下载器即被添加到 PeerBanHelper 中，此时可能提示状态未知。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/b24011a1-bfdb-43b3-9fec-6709c1acc742)

请耐心等待一会儿，如果连接成功，将会提示状态良好。

![image](https://github.com/PBH-BTN/PBH-Adapter-BiglyBT/assets/30802565/8c6fcc58-6d59-4daf-beed-0e0afee1eab0)

## 兼容与故障排查

- 插件 2.1 保留既有插件 ID 和旧版 API。支持协议 2 的 PeerBanHelper 会通过 `/metadata` 选择批量 Peer 采集和版本化封禁同步，两端可以先后升级。
- 请在 BiglyBT 中开启 IP 过滤。插件的封禁列表由 PeerBanHelper 管理，插件重启后由 PeerBanHelper 重新发送。插件不会覆盖 BiglyBT 原有的过滤规则。BiglyBT 自身对局域网、本机和排除任务的过滤例外仍适用。
- 若新版 PeerBanHelper 报告 IP 过滤已关闭，检查 BiglyBT 的 IP 过滤设置。列表已收到时，BiglyBT 仍可能因上述例外允许个别连接。
- 如果旧部署依赖同一 IP 的多个 Peer 连接或客户端标识后缀，请在 BiglyBT 中检查相应设置。插件不会强制开启同 IP 多连接，新安装默认关闭客户端标识改写。
