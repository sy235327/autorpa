# [pigeon-fork] license 数据模型（随 Base.metadata 建表）
#encoding=utf-8
import time

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    Index,
)
from sqlalchemy.types import JSON

from ..models import BaseDatabaseModel


class LicenseBatch(BaseDatabaseModel):
    __tablename__ = "license_batch"
    batch_no            = Column(String, unique=True, index=True)
    script_id           = Column(Integer, ForeignKey("script.id", ondelete="CASCADE"))
    update_policy       = Column(String, default="confirm")     # 遗留字段（新模型不使用）
    group_tag           = Column(String, default="")            # [M3] 分组名（默认=批次号，可切换/合并）
    note                = Column(String(4096), nullable=True)
    create_by           = Column(String, nullable=True)
    create_time         = Column(BigInteger, default=time.time)


class LicenseCard(BaseDatabaseModel):
    __tablename__ = "license_card"
    code                = Column(String, unique=True, index=True)
    batch_id            = Column(Integer, ForeignKey("license_batch.id", ondelete="CASCADE"))
    status              = Column(String, default="enable")      # enable | disabled
    expire_at           = Column(BigInteger, nullable=True)     # epoch 秒；NULL=永久
    device_limit        = Column(Integer, default=1)            # 0=不限
    note                = Column(String(4096), nullable=True)
    create_time         = Column(BigInteger, default=time.time)
    update_time         = Column(BigInteger, default=time.time)
    # [M3] 解绑配额（app 自助解绑）：0=不限
    unbind_total_limit  = Column(Integer, default=0)            # 总次数
    unbind_daily_limit  = Column(Integer, default=0)            # 每天次数
    unbind_interval     = Column(Integer, default=0)            # 两次解绑最小间隔（秒）
    unbind_used         = Column(Integer, default=0)            # 已用总次数
    unbind_day          = Column(String, default="")            # 当前统计日 YYYY-MM-DD
    unbind_day_used     = Column(Integer, default=0)            # 当日已用
    last_unbind_time    = Column(BigInteger, default=0)         # 上次解绑时间


class LicenseBinding(BaseDatabaseModel):
    __tablename__ = "license_binding"
    card_id             = Column(Integer, ForeignKey("license_card.id", ondelete="CASCADE"))
    dev_id              = Column(String, index=True)            # lamda unique_id
    domain              = Column(String, nullable=True)         # pigeon domain
    device_info         = Column(JSON, default=dict, nullable=True)
    active              = Column(Boolean, default=False)        # 设备当前激活的卡
    session_token       = Column(String, unique=True, index=True, nullable=True)
    run_version_id      = Column(Integer, nullable=True)        # [M3] 设备当前运行的脚本版本（未设=最新）
    bind_time           = Column(BigInteger, default=time.time)
    last_seen           = Column(BigInteger, default=0)
    state               = Column(String, default="active")      # active | kicked | unbound

    __table_args__ = (
        UniqueConstraint("card_id", "dev_id", name="license_binding_card_device_uniq"),
        Index("license_binding_dev_idx", "dev_id"),
    )


class LicenseBlacklist(BaseDatabaseModel):
    """[M3] 设备黑名单：拉黑后无法使用卡密（verify/switch/启动 被拒绝）"""
    __tablename__ = "license_blacklist"
    dev_id              = Column(String, unique=True, index=True)
    reason              = Column(String(4096), nullable=True)
    create_by           = Column(String, nullable=True)
    create_time         = Column(BigInteger, default=time.time)


class LicenseAppRelease(BaseDatabaseModel):
    """[M3] App 版本管理（单行）：版本号 + 下载地址，app 据此提示更新"""
    __tablename__ = "license_app_release"
    version             = Column(String, default="")
    url                 = Column(String, default="")
    note                = Column(String(4096), default="")
    update_time         = Column(BigInteger, default=0)


class LicenseAudit(BaseDatabaseModel):
    __tablename__ = "license_audit"
    actor               = Column(String, nullable=True)
    action              = Column(String, index=True)
    card_id             = Column(Integer, nullable=True)
    dev_id              = Column(String, nullable=True, index=True)
    batch_no            = Column(String, nullable=True)
    detail              = Column(JSON, default=dict, nullable=True)
    time                = Column(BigInteger, default=time.time)
