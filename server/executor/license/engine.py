# [pigeon-fork] 设备迁移机器（engine）
# 切卡 / 确认式更新 / 封卡止损 共用：设备在【组】之间的迁移 + 任务同步
# 所有写操作与"管理后台手工操作"等价（组管理 + 脚本版本切换），不引入私有调度逻辑
#encoding=utf-8
import os
import time

from ..models import (
    Device,
    DeviceJob,
    DeviceJobState,
    Group,
    Job,
    JobMode,
    JobState,
    Script,
    ScriptVersion,
    Task,
    TaskState,
    UserDevice,
)
from .models import (
    LicenseAppRelease,
    LicenseAudit,
    LicenseBatch,
    LicenseBinding,
    LicenseBlacklist,
    LicenseCard,
)
from .util import gen_session_token, now

POLICY_FORCE = "force"
POLICY_CONFIRM = "confirm"


def max_cards_per_device():
    try:
        return int(os.environ.get("LICENSE_MAX_CARDS_PER_DEVICE", "5"))
    except ValueError:
        return 5


def audit(session, actor, action, card=None, dev_id=None, batch_no=None, **detail):
    row = LicenseAudit(actor=actor, action=action,
                       card_id=card.id if card else None,
                       dev_id=dev_id, batch_no=batch_no,
                       detail=detail or None)
    session.add(row)
    session.flush()
    return row


def ensure_userdevice(session, user_id, device_id):
    row = session.query(UserDevice).filter(
        UserDevice.user_id == user_id,
        UserDevice.device_id == device_id).first()
    if not row:
        row = UserDevice(user_id=user_id, device_id=device_id)
        session.add(row)
        session.flush()
    return row


def latest_version(session, script):
    return session.query(ScriptVersion).filter(
        ScriptVersion.parent_id == script.id).order_by(
        ScriptVersion.id.desc()).first()


def version_group_name(tag, version_id):
    return f"license:{tag}:v{version_id}"


def ensure_version_group_job(session, script, owner_id, version, tag):
    """[M3] 为「批次分组标签 + 版本」确保组与该组的 RUNNING job（幂等 + 作业自愈）"""
    name = version_group_name(tag, version.id)
    group = session.query(Group).filter(Group.owner_id == owner_id,
                                        Group.name == name).first()
    if not group:
        group = Group(owner_id=owner_id, name=name,
                      description=f"[license] {tag} v{version.version} (script#{script.id})")
        session.add(group)
        session.flush()
    job = session.query(Job).filter(Job.group_id == group.id).order_by(
        Job.id.asc()).first()
    if not job:
        job = Job(name=f"{name}#job", owner_id=owner_id, group_id=group.id,
                  script_id=version.id, state=JobState.RUNNING.value,
                  mode=JobMode.LOOP.value, interval=0,
                  description="pigeon-fork license job")
        session.add(job)
        session.flush()
    else:
        # 任务派发要求 Job.state == running；作业被人工暂停/停止时自动恢复
        if job.state != JobState.RUNNING.value or (job.stop_time or 0) > 0:
            job.state = JobState.RUNNING.value
            job.stop_time = 0
            session.query(DeviceJob).filter(DeviceJob.job_id == job.id).update(
                dict(state=DeviceJobState.INITIAL.value, error=None),
                synchronize_session=False)
            session.flush()
    return group, job


def switch_job_version(session, job, version):
    """对齐上游 script-version 切换：换版本 + 重置 DeviceJob（触发重推）"""
    job.script_id = version.id
    session.query(DeviceJob).filter(DeviceJob.job_id == job.id).update(
        dict(state=DeviceJobState.INITIAL.value, error=None),
        synchronize_session=False)
    session.flush()


def stop_device(session, device, revoke=True):
    """停发：移出全部组；revoke：撤销该设备在途任务（对齐上游 stop 逻辑）"""
    session.query(UserDevice).filter(
        UserDevice.device_id == device.id).update(
        dict(group_id=None), synchronize_session=False)
    if not revoke or not device.dev_id:
        return 0
    rows = session.query(Task.task_id).filter(
        Task.device_id == device.id,
        Task.state.in_([TaskState.PENDING.value, TaskState.ISSUED.value,
                        TaskState.RUNNING.value])).all()
    ids = [r[0] for r in rows]
    if ids:
        from ..handlers.event import send_node_command
        send_node_command("task/revoke", dict(task_id=ids), device.dev_id)
    return len(ids)


def activate(session, card, device, actor="card", action="activate"):
    """激活/切换核心：停旧卡脚本 -> 绑定（授权）-> 置为未启动(idle)。
    [M3] 激活不再自动入组（启动脚本由 resume/启动 按钮完成）；
    若目标卡密当前正在运行则保持不变（幂等）。返回 (binding, batch, script, None)"""
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first()
    script = session.query(Script).filter(Script.id == batch.script_id).first()
    binding = session.query(LicenseBinding).filter(
        LicenseBinding.card_id == card.id,
        LicenseBinding.dev_id == device.dev_id).first()
    same_running = bool(binding and binding.state == "active")
    if not same_running:
        # 切换/首次激活：停止旧卡脚本（退组 + 撤销在途任务）
        stop_device(session, device, revoke=True)
    if not binding:
        binding = LicenseBinding(card_id=card.id, dev_id=device.dev_id,
                                 session_token=gen_session_token())
        session.add(binding)
        session.flush()
    binding.domain = device.domain
    binding.device_info = dict(brand=device.brand, model=device.model,
                               sdk=device.sdk, comment=device.comment)
    binding.state = "active" if same_running else "idle"
    binding.last_seen = now()
    session.query(LicenseBinding).filter(
        LicenseBinding.dev_id == device.dev_id,
        LicenseBinding.id != binding.id).update(
        dict(active=False), synchronize_session=False)
    # [M3] 切换语义：被切走的卡密脚本已停止（设备已退出旧组）→ 状态标记为 paused
    session.query(LicenseBinding).filter(
        LicenseBinding.dev_id == device.dev_id,
        LicenseBinding.id != binding.id,
        LicenseBinding.state == "active").update(
        dict(state="paused"), synchronize_session=False)
    binding.active = True
    session.flush()
    audit(session, actor, action, card=card, dev_id=device.dev_id,
          batch_no=batch.batch_no, state=binding.state)
    return binding, batch, script, None


def run_version(session, binding, script):
    """[M3] 该绑定应运行的版本：run_version_id 有效则用它，否则最新版本"""
    if binding.run_version_id:
        version = session.query(ScriptVersion).filter(
            ScriptVersion.id == binding.run_version_id,
            ScriptVersion.parent_id == script.id).first()
        if version:
            return version
    return latest_version(session, script)


def join_version_group(session, script, device, version, tag):
    """[M3] 启动脚本：确保该批次的版本组/作业（作业自愈）并让设备入组"""
    group, job = ensure_version_group_job(session, script, script.owner_id,
                                          version, tag)
    row = ensure_userdevice(session, script.owner_id, device.id)
    row.group_id = group.id
    session.flush()
    return group, job


def kill_card(session, card, actor="admin"):
    """封卡：全部绑定设备停发 + 撤销在途任务"""
    bindings = session.query(LicenseBinding).filter(
        LicenseBinding.card_id == card.id,
        LicenseBinding.state == "active").all()
    count = 0
    for b in bindings:
        device = session.query(Device).filter(
            Device.dev_id == b.dev_id, Device.is_deleted == False).first()
        if device:
            stop_device(session, device, revoke=True)
            count += 1
        b.state = "kicked"
        b.active = False
    card.status = "disabled"
    card.update_time = now()
    audit(session, actor, "disable", card=card, detail=dict(devices=count))
    return count


def restore_card(session, card, actor="admin"):
    """恢复：对绑定设备重新激活回组"""
    card.status = "enable"
    card.update_time = now()
    bindings = session.query(LicenseBinding).filter(
        LicenseBinding.card_id == card.id,
        LicenseBinding.state == "kicked").all()
    count = 0
    for b in bindings:
        device = session.query(Device).filter(
            Device.dev_id == b.dev_id, Device.is_deleted == False).first()
        if device:
            try:
                activate(session, card, device, actor=actor)
                count += 1
            except ValueError:
                # 设备被拉黑等异常：跳过，不影响整批恢复
                continue
    audit(session, actor, "restore", card=card, detail=dict(devices=count))
    return count


def pause(session, binding, device, actor="app"):
    """暂停脚本：停发 + 撤销在途任务；绑定保持授权（state=paused，可一键启动）"""
    card = session.query(LicenseCard).filter(
        LicenseCard.id == binding.card_id).first()
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first() if card else None
    count = stop_device(session, device, revoke=True)
    binding.state = "paused"
    session.flush()
    audit(session, actor, "pause", card=card, dev_id=device.dev_id,
          batch_no=(batch.batch_no if batch else None), tasks=count)
    return count


def resume(session, binding, device, actor="app"):
    """[M3] 启动脚本：绑定（幂等）-> 入组运行（运行版本=上次运行的版本，缺省最新）"""
    card = session.query(LicenseCard).filter(
        LicenseCard.id == binding.card_id).first()
    if not card or card.status != "enable":
        raise ValueError("card disabled or not exist")
    if is_blacklisted(session, device.dev_id):
        raise ValueError("device_blacklisted")
    binding, batch, script, _ = activate(session, card, device,
                                         actor=actor, action="start")
    version = run_version(session, binding, script)
    if not version:
        raise ValueError("script_has_no_version")
    tag = batch.group_tag or batch.batch_no
    group, job = join_version_group(session, script, device, version, tag)
    binding.run_version_id = version.id
    binding.state = "active"
    binding.active = True
    session.flush()
    return job.id


def stop_run(session, binding, device, actor="app"):
    """结束脚本：停发 + 撤销在途任务（state=stopped，可再次启动）"""
    card = session.query(LicenseCard).filter(
        LicenseCard.id == binding.card_id).first()
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first() if card else None
    count = stop_device(session, device, revoke=True)
    binding.state = "stopped"
    session.flush()
    audit(session, actor, "stop", card=card, dev_id=device.dev_id,
          batch_no=(batch.batch_no if batch else None), tasks=count)
    return count


def unbind_quota_state(card):
    """[M3] 解绑配额状态（展示 / 校验共用）"""
    today = time.strftime("%Y-%m-%d", time.localtime(now()))
    return dict(
        total_limit=card.unbind_total_limit or 0,
        total_used=card.unbind_used or 0,
        daily_limit=card.unbind_daily_limit or 0,
        day_used=((card.unbind_day_used or 0)
                  if (card.unbind_day or "") == today else 0),
        interval=card.unbind_interval or 0,
        last_unbind_time=card.last_unbind_time or 0,
    )


def check_unbind_quota(card):
    """校验解绑配额（含当日计数滚动）；不通过抛 ValueError（错误码）"""
    today = time.strftime("%Y-%m-%d", time.localtime(now()))
    if (card.unbind_day or "") != today:
        card.unbind_day = today
        card.unbind_day_used = 0
    if card.unbind_total_limit and \
            (card.unbind_used or 0) >= card.unbind_total_limit:
        raise ValueError("unbind_total_limit_reached")
    if card.unbind_daily_limit and \
            (card.unbind_day_used or 0) >= card.unbind_daily_limit:
        raise ValueError("unbind_daily_limit_reached")
    interval = card.unbind_interval or 0
    last = card.last_unbind_time or 0
    if interval and last and (now() - last) < interval:
        raise ValueError("unbind_interval_not_reached_%ds"
                         % int(interval - (now() - last)))


def consume_unbind_quota(card):
    card.unbind_used = (card.unbind_used or 0) + 1
    card.unbind_day_used = (card.unbind_day_used or 0) + 1
    card.last_unbind_time = now()


def unbind(session, card, dev_id, actor="app"):
    """解绑卡密下的某设备（app 自助）：配额校验 -> 停发/撤销 -> 标记解绑 -> 计数 -> 审计"""
    binding = session.query(LicenseBinding).filter(
        LicenseBinding.card_id == card.id,
        LicenseBinding.dev_id == dev_id,
        LicenseBinding.state != "unbound").first()
    if not binding:
        raise ValueError("binding_not_exist")
    check_unbind_quota(card)
    device = session.query(Device).filter(
        Device.dev_id == dev_id, Device.is_deleted == False).first()
    if binding.active and device:
        stop_device(session, device, revoke=True)
    binding.state = "unbound"
    binding.active = False
    binding.session_token = gen_session_token()   # 旧会话立即失效
    consume_unbind_quota(card)
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first()
    card.update_time = now()
    session.flush()
    audit(session, actor, "unbind", card=card, dev_id=dev_id,
          batch_no=(batch.batch_no if batch else None),
          detail=dict(quota=unbind_quota_state(card)))
    return unbind_quota_state(card)


def get_app_release(session):
    """[M3] App 版本信息（单行，无则创建空行）"""
    row = session.query(LicenseAppRelease).order_by(
        LicenseAppRelease.id.asc()).first()
    if not row:
        row = LicenseAppRelease(version="", url="", note="", update_time=0)
        session.add(row)
        session.flush()
    return row


def set_app_release(session, version, url, note="", actor="admin"):
    """[M3] 设置 App 版本号与下载地址（app 据此提示更新）"""
    row = get_app_release(session)
    row.version = (version or "").strip()
    row.url = (url or "").strip()
    row.note = (note or "").strip()
    row.update_time = now()
    session.flush()
    audit(session, actor, "app_release",
          detail=dict(version=row.version, url=row.url))
    return row


def is_blacklisted(session, dev_id):
    """[M3] 设备是否被拉黑"""
    if not dev_id:
        return False
    return session.query(LicenseBlacklist).filter(
        LicenseBlacklist.dev_id == dev_id).first() is not None


def set_blacklist(session, dev_id, blocked, reason="", actor="admin"):
    """[M3] 拉黑/解除拉黑设备；拉黑时立即停发并撤销在途任务"""
    dev_id = (dev_id or "").strip()
    if not dev_id:
        raise ValueError("missing_dev_id")
    row = session.query(LicenseBlacklist).filter(
        LicenseBlacklist.dev_id == dev_id).first()
    if blocked:
        if not row:
            row = LicenseBlacklist(dev_id=dev_id, reason=reason,
                                   create_by=actor, create_time=now())
            session.add(row)
        else:
            row.reason = reason or row.reason
        device = session.query(Device).filter(
            Device.dev_id == dev_id, Device.is_deleted == False).first()
        if device:
            stop_device(session, device, revoke=True)
        session.query(LicenseBinding).filter(
            LicenseBinding.dev_id == dev_id,
            LicenseBinding.state == "active").update(
            dict(state="paused"), synchronize_session=False)
        session.flush()
        audit(session, actor, "blacklist", dev_id=dev_id,
              detail=dict(blocked=True, reason=reason or ""))
    else:
        if row:
            session.delete(row)
            session.flush()
        audit(session, actor, "blacklist", dev_id=dev_id,
              detail=dict(blocked=False))
    return bool(blocked)


def verify_max_fails():
    try:
        return int(os.environ.get("LICENSE_VERIFY_MAX_FAILS", "5"))
    except ValueError:
        return 5


def verify_lock_seconds():
    try:
        return int(os.environ.get("LICENSE_VERIFY_LOCK_SECONDS", "600"))
    except ValueError:
        return 600


def verify_guard_check(session, dev_id):
    """[M3] 防爆破：同一设备在锁定窗口内口令失败达到阈值则拒绝（错误带剩余秒数）"""
    if not dev_id:
        return
    limit = verify_max_fails()
    window = verify_lock_seconds()
    rows = session.query(LicenseAudit.time).filter(
        LicenseAudit.action == "verify_fail",
        LicenseAudit.dev_id == dev_id).order_by(
        LicenseAudit.id.desc()).limit(limit).all()
    if len(rows) < limit:
        return
    boundary = rows[-1][0] or 0
    remain = int(boundary + window - now())
    if remain > 0:
        raise ValueError("verify_locked_retry_in_%ds" % remain)


def verify_guard_record(session, dev_id):
    """[M3] 记录一次口令校验失败（防爆破计数）"""
    if not dev_id:
        return
    audit(session, "client", "verify_fail", dev_id=dev_id)


def force_update(session, script, actor="admin", version=None):
    """[M3] 强制更新：把该脚本相关的所有设备更新到目标版本（缺省最新版本）。
    - 正在运行的设备：迁移到目标版本组（立即重下发该版本任务）
    - 未运行的设备：记录 run_version_id，下次启动时生效
    回滚同样适用（对旧版本点「强制更新」即可）。"""
    if version is None:
        version = latest_version(session, script)
    result = dict(version=version.version if version else None,
                  devices=0, running=0, job_id=None)
    if not version:
        return result
    rows = session.query(LicenseBinding, LicenseBatch).join(
        LicenseCard, LicenseCard.id == LicenseBinding.card_id).join(
        LicenseBatch, LicenseBatch.id == LicenseCard.batch_id).filter(
        LicenseBatch.script_id == script.id,
        LicenseBinding.state != "unbound").all()
    groups = {}
    for b, batch in rows:
        b.run_version_id = version.id
        result["devices"] += 1
        if b.state != "active":
            continue
        device = session.query(Device).filter(
            Device.dev_id == b.dev_id, Device.is_deleted == False).first()
        if not device:
            continue
        tag = batch.group_tag or batch.batch_no
        if tag not in groups:
            groups[tag] = ensure_version_group_job(session, script,
                                                   script.owner_id, version,
                                                   tag)
            result["job_id"] = groups[tag][1].id
        row = ensure_userdevice(session, script.owner_id, device.id)
        row.group_id = groups[tag][0].id
        result["running"] += 1
    session.flush()
    audit(session, actor, "force", detail=result)
    return result


def pending_update(session, binding):
    """更新检测：设备当前组 job 版本是否落后于最新版本"""
    card = session.query(LicenseCard).filter(
        LicenseCard.id == binding.card_id).first()
    if not card:
        return False, None
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first()
    script = session.query(Script).filter(Script.id == batch.script_id).first()
    version = latest_version(session, script)
    device = session.query(Device).filter(
        Device.dev_id == binding.dev_id, Device.is_deleted == False).first()
    if not device or not version:
        return False, None
    row = session.query(UserDevice).filter(
        UserDevice.device_id == device.id,
        UserDevice.user_id == script.owner_id).first()
    if not row or not row.group_id:
        return False, None
    job = session.query(Job).filter(Job.group_id == row.group_id).first()
    if not job:
        return False, None
    return (job.script_id != version.id), version


def accept_update(session, binding, device):
    """[M3] 用户自行更新：迁移到最新版本组并记住运行版本"""
    card = session.query(LicenseCard).filter(
        LicenseCard.id == binding.card_id).first()
    batch = session.query(LicenseBatch).filter(
        LicenseBatch.id == card.batch_id).first()
    script = session.query(Script).filter(Script.id == batch.script_id).first()
    version = latest_version(session, script)
    if not version:
        raise ValueError("script has no version")
    stop_device(session, device, revoke=True)
    tag = batch.group_tag or batch.batch_no
    group, job = join_version_group(session, script, device, version, tag)
    binding.run_version_id = version.id
    session.flush()
    audit(session, "app", "accept", card=card, dev_id=device.dev_id,
          batch_no=batch.batch_no, version=version.version)
    return True, job.id
