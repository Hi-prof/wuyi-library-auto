"""号池领域模型与业务异常。

本模块定义 spec ``account-pool-tri-sync`` 的领域核心：

* 三池状态枚举 ``PoolStatus`` 与迁移来源 ``PoolMigrationTrigger``、客户端来源 ``ClientKind``。
* 账号 / 活跃池清单 / 活跃池详情 / 自动任务等数据类。
* 批量导入入参 / 出参与条目结果数据类。
* 业务异常体系 ``AccountPoolError`` 及其子类。

设计要点：

* 所有时间字段统一使用带时区的 :class:`datetime.datetime` (UTC aware)；落库时按 UTC ISO8601
  文本读写，UI 渲染由表现层负责。
* 数据类全部使用 ``frozen=True``，避免领域对象在服务层之外被原地修改。
* 批量导入响应里的 ``status`` 与 ``reason`` 用枚举固定取值，方便前端按字符串值翻译文案。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class PoolStatus(str, Enum):
    """三池状态枚举。

    ``active`` / ``suspended`` / ``idle`` 三个字面量与数据库 ``accounts.pool_status``
    列的 CHECK 约束一一对应，**禁止新增新值**；任何新池都会破坏 Requirement 1.1。
    继承 ``str`` 是为了 JSON 序列化与 SQL 绑定可以直接拿到字面量字符串。
    """

    ACTIVE = "active"
    SUSPENDED = "suspended"
    IDLE = "idle"


class PoolMigrationTrigger(str, Enum):
    """池迁移触发源。

    与 ``pool_audit_log.trigger_source`` 列严格对应，覆盖手动迁移、客户端上报拉黑、
    Reaper Job 到期回收、批量导入入池、Random_Pick 入池、系统启动迁移等场景。
    """

    MANUAL = "manual"
    BLACKLIST = "blacklist"
    EXPIRE = "expire"
    IMPORT = "import"
    RANDOM_PICK = "random_pick"
    SYSTEM = "system"


class ClientKind(str, Enum):
    """请求方客户端类型。

    用于审计日志 ``client_kind`` 列与 token 签发记录；``WEB`` / ``SYSTEM`` 仅供服务端内部
    流程使用，不会出现在外部客户端的 token 上。
    """

    WINDOW = "window"
    ANDROID = "android"
    WEB = "web"
    SYSTEM = "system"


class BulkImportItemStatus(str, Enum):
    """批量导入条目级状态。"""

    OK = "ok"
    REJECTED = "rejected"


class BulkImportRejectReason(str, Enum):
    """批量导入条目级拒绝原因。

    覆盖 design「Components and Interfaces」批量导入响应示例中的全部 ``reason`` 取值。
    """

    VALIDATION_ERROR = "validation_error"
    DUPLICATE_STUDENT_ID = "duplicate_student_id"
    POOL_FULL = "pool_full"


@dataclass(frozen=True)
class CustomWindow:
    """自动任务的自定义生效时段。

    与 ``automation_tasks.custom_windows_json`` 中的单个对象一一对应，字段含义见 design
    「Data Models」一节：``date`` 为目标日期 (``YYYY-MM-DD``)，``start_hour`` / ``end_hour``
    为整点小时（约束 ``0 <= start_hour < end_hour <= 23``，由服务层校验）。
    """

    date: str
    start_hour: int
    end_hour: int


@dataclass(frozen=True)
class AutomationTask:
    """单个 Automation_Task 的领域对象。

    ``revision`` 字段用于 Automation_Task_Sync_API 的乐观并发控制；服务端自动预约
    服务会读取已启用任务执行补约。``deleted_at`` 为软删除标记，``None`` 表示未删除。
    """

    task_id: int
    account_id: int
    room_name: str
    seat_number: str
    mode: str
    custom_windows: tuple[CustomWindow, ...]
    enabled: bool
    revision: int
    created_at: datetime
    updated_at: datetime
    deleted_at: datetime | None


@dataclass(frozen=True)
class AccountPoolEntry:
    """三池视图下的账号实体。

    包含池状态、池迁移元信息、暂停时段、账号级 ``revision``。``suspended_at`` 与
    ``suspension_expires_at`` 仅当 ``pool_status == PoolStatus.SUSPENDED`` 时为非 ``None``，
    其它状态下必为 ``None``（与数据库 CHECK 约束保持一致）。
    """

    account_id: int
    student_id: str
    display_name: str
    pool_status: PoolStatus
    pool_updated_at: datetime
    pool_previous: str
    suspended_at: datetime | None
    suspension_expires_at: datetime | None
    revision: int


@dataclass(frozen=True)
class ActiveAccountListItem:
    """Active_Account_List_API（接口 A）单条记录。

    严格不含密码、cookie、session token 与自动任务详情；``pool_status`` 永远是 ``ACTIVE``。
    """

    account_id: int
    student_id: str
    display_name: str
    pool_status: PoolStatus
    updated_at: datetime


@dataclass(frozen=True)
class ActiveAccountDetail:
    """Active_Account_Detail_API（接口 B）的响应载荷。

    仅在账号属于 Active_Pool 时构造；``password`` 字段是经 AES-GCM 解密后的明文，
    服务层 / REST 层在记日志或写审计前必须用 ``scrub`` 过滤掉。
    """

    account_id: int
    student_id: str
    display_name: str
    password: str
    revision: int
    automation_tasks: tuple[AutomationTask, ...]


@dataclass(frozen=True)
class BulkImportRow:
    """批量导入的单条入参。

    最小必填字段为 ``student_id`` 与 ``password``（Requirement 5-Q1 默认值）；其余字段
    在前端缺省时按空字符串传入，由服务层做去空白、长度与唯一性校验。
    ``source_row`` 是用户提交的原始行号（从 1 开始），用于在响应里回带定位错误。
    """

    student_id: str
    password: str
    display_name: str = ""
    note: str = ""
    default_room_name: str = ""
    default_seat_number: str = ""
    login_url: str = ""
    source_row: int = 0


@dataclass(frozen=True)
class BulkImportItemResult:
    """批量导入的单条结果。

    成功条目 ``status == OK``，``account_id`` 为新写入行的主键、``reason`` 为 ``None``；
    失败条目 ``status == REJECTED``，``account_id`` 为 ``None``、``reason`` 给出原因。
    """

    source_row: int
    student_id: str
    status: BulkImportItemStatus
    account_id: int | None
    reason: BulkImportRejectReason | None


@dataclass(frozen=True)
class BulkImportResult:
    """批量导入整体结果。

    ``total == success_count + failure_count``；``items`` 与入参 ``rows`` 一一对应、顺序保持
    一致，便于前端按行展示。
    """

    total: int
    success_count: int
    failure_count: int
    items: tuple[BulkImportItemResult, ...] = field(default_factory=tuple)


# ----------------------------- 业务异常体系 -----------------------------


class AccountPoolError(Exception):
    """号池领域所有业务异常的根。

    REST 层通过 FastAPI ``exception_handler`` 把子类映射为 design「Error Handling」表中
    的 HTTP 响应；非业务异常（数据库异常等）不应被该体系捕获。
    """


class PoolCapacityExceeded(AccountPoolError):
    """触发 Pool_Capacity 总上限或 Active_Pool 单池上限。

    REST 层映射为 ``422 {"reason":"pool_full"}``；批量导入条目级失败仍走 ``POOL_FULL``
    枚举 reason，不抛此异常。
    """


class IllegalPoolTransition(AccountPoolError):
    """违反合法迁移矩阵（典型场景：``idle → suspended`` 直达）。

    REST 层映射为 ``422 {"reason":"illegal_transition"}``。
    """


class AccountNotInActivePool(AccountPoolError):
    """请求引用的账号不在 Active_Pool。

    覆盖三种内部场景：账号根本不存在、在 Suspended_Pool / Idle_Pool、已被软删。
    REST 层统一翻译为 ``404 {"detail":"account not found"}``，且字节级一致以避免泄露
    账号真实状态（Requirement 6.5）。
    """


class RevisionConflict(AccountPoolError):
    """Automation_Task 上传时 ``revision`` 与服务端最新版本不一致。

    REST 层映射为 ``409 {"reason":"revision_conflict","server_revision":...,
    "server_payload":{...}}``。``server_payload`` 为服务端最新一份载荷，便于客户端
    展示冲突解决 UI。
    """

    def __init__(self, server_revision: int, server_payload: dict) -> None:
        super().__init__(
            f"revision conflict: server_revision={server_revision}",
        )
        self.server_revision = server_revision
        self.server_payload = server_payload


class DuplicateStudentId(AccountPoolError):
    """命中 ``UNIQUE(student_id, login_url)`` 唯一键。

    通常由批量导入与新建账号路径触发；批量导入条目级走 ``DUPLICATE_STUDENT_ID``
    枚举 reason，仅在原子操作场景才抛此异常。
    """


class MissingLoginCredentials(AccountPoolError):
    """手动迁入 Active_Pool 时缺少有效登录所需字段。

    与 Requirement 2-Q3 默认值一致：阻止迁移并返回错误，由 REST 层映射为
    ``422 {"reason":"missing_login_credentials"}``。
    """


class IdleEmpty(AccountPoolError):
    """Random_Pick 触发时 Idle_Pool 为空。

    REST 层映射为 ``422 {"reason":"idle_empty"}``，前端展示「未启用池为空」文案。
    """
