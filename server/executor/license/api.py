# [pigeon-fork] license HTTP API（管理端 + 客户端）
# 全部操作最终落在"设备迁移机器"（engine.py）上，与后台手工操作等价
#encoding=utf-8
from server.service import BaseHttpService
from sqlalchemy import or_

from ..handlers.event import issue_task_by_job, sync
from ..models import Device, Group, Job, Script, ScriptVersion, UserDevice, session_scope
from . import engine
from .models import (LicenseAudit, LicenseBatch, LicenseBinding,
                     LicenseBlacklist, LicenseCard)
from .util import check_card_code, gen_batch_no, gen_card_code, now


def mask_code(code):
    return code[:4] + "-****-****-" + code[-4:]


def kick_sync(job_id=None):
    """事务提交后触发：组/任务对账同步 + 需要时补投最新任务"""
    sync.apply_async()
    if job_id:
        issue_task_by_job.apply_async((job_id,), countdown=3)


def activate_code(session, code, dev_id, actor="card"):
    """校验 + 激活（verify/switch 共用）。未入网设备返回 pending_enroll。"""
    code = (code or "").strip().upper()
    if not check_card_code(code):
        raise ValueError("invalid card code")
    card = session.query(LicenseCard).filter(LicenseCard.code == code).first()
    if not card or card.status != "enable":
        raise ValueError("card disabled or not exist")
    if card.expire_at and card.expire_at < now():
        raise ValueError("card expired")
    device = session.query(Device).filter(Device.dev_id == dev_id,
                                          Device.is_deleted == False).first()
    if not device:
        return dict(status="pending_enroll"), None
    if engine.is_blacklisted(session, dev_id):
        raise ValueError("device_blacklisted")
    existing = session.query(LicenseBinding).filter(
        LicenseBinding.card_id == card.id,
        LicenseBinding.dev_id == dev_id).first()
    if not existing:
        bound = session.query(LicenseBinding).filter(
            LicenseBinding.card_id == card.id).count()
        if card.device_limit and bound >= card.device_limit:
            raise ValueError("card device limit reached")
        cap = engine.max_cards_per_device()
        if cap:
            per_dev = session.query(LicenseBinding).filter(
                LicenseBinding.dev_id == dev_id).count()
            if per_dev >= cap:
                raise ValueError("too many cards on this device")
    binding, batch, script, _ = engine.activate(session, card, device,
                                                actor=actor)
    data = dict(status="ok",
                session_token=binding.session_token,
                state=binding.state,
                card=dict(code=mask_code(card.code), expire_at=card.expire_at,
                          status=card.status),
                script=dict(id=script.id, name=script.name,
                            description=script.description),
                batch=dict(batch_no=batch.batch_no,
                           update_policy=batch.update_policy))
    return data, None


class LicenseAdminBase(BaseHttpService):
    def admin(self, session):
        return self.get_login_user_admin(session=session)


class LicenseClientBase(BaseHttpService):
    def binding(self, session):
        token = self.request.headers.get("X-License-Token", "")
        binding = None
        if token:
            binding = session.query(LicenseBinding).filter(
                LicenseBinding.session_token == token).first()
        binding or self.throw(401, "L1001", message="Invalid license session")
        return binding

    def effective_binding(self, session, binding):
        """[M3] 设备当前实际启用的卡绑定：
        app 的 token 可能属于设备上另一张未启用的卡（例如换卡由后台/强制更新触发），
        运行控制类操作（暂停/结束/更新）以"设备正在跑的那张卡"为准。"""
        eff = session.query(LicenseBinding).filter(
            LicenseBinding.dev_id == binding.dev_id,
            LicenseBinding.active == True).first()
        return eff or binding

    def device(self, session, binding):
        device = session.query(Device).filter(
            Device.dev_id == binding.dev_id,
            Device.is_deleted == False).first()
        device or self.throw(404, "L1002", message="Device not found")
        return device


# ---------------- 管理端 ----------------

class LicenseBatchCreateHandler(LicenseAdminBase):
    def post(self):
        with session_scope() as session:
            user = self.admin(session)
            script_id = self.get_argument("script_id", type=int)
            count = min(max(self.get_argument("count", 1, type=int), 1), 1000)
            update_policy = self.get_argument("update_policy", "confirm")
            update_policy = update_policy if update_policy in (
                "force", "confirm") else "confirm"
            expire_days = self.get_argument("expire_days", 0, type=int)
            device_limit = self.get_argument("device_limit", 1, type=int)
            unbind_total_limit = max(0, self.get_argument(
                "unbind_total_limit", 0, type=int))
            unbind_daily_limit = max(0, self.get_argument(
                "unbind_daily_limit", 0, type=int))
            unbind_interval = max(0, self.get_argument(
                "unbind_interval", 0, type=int))
            note = self.get_argument("note", "")
            script = session.query(Script).filter(
                Script.id == script_id, Script.is_deleted == False).first()
            script or self.throw(404, "S4001", message="Script does not exist")
            batch_no = gen_batch_no()
            group_tag = (self.get_argument("group_tag", "") or "").strip() \
                or batch_no
            batch = LicenseBatch(batch_no=batch_no, script_id=script.id,
                                 update_policy=update_policy, note=note,
                                 group_tag=group_tag, create_by=user.name)
            session.add(batch)
            session.flush()
            # [M3] 生成卡密时即为该批次创建分组（license:<批次号>:v<最新版本>）
            version = engine.latest_version(session, script)
            if version:
                engine.ensure_version_group_job(session, script,
                                                script.owner_id,
                                                version, group_tag)
            expire_at = None if expire_days <= 0 else now() + expire_days * 86400
            codes = []
            for _ in range(count):
                code = gen_card_code()
                session.add(LicenseCard(code=code, batch_id=batch.id,
                                        expire_at=expire_at,
                                        device_limit=device_limit, note=note,
                                        unbind_total_limit=unbind_total_limit,
                                        unbind_daily_limit=unbind_daily_limit,
                                        unbind_interval=unbind_interval))
                codes.append(code)
            engine.audit(session, user.name, "create", batch_no=batch.batch_no,
                         detail=dict(count=count, policy=update_policy,
                                     script_id=script.id))
            data = dict(batch=dict(id=batch.id, batch_no=batch.batch_no,
                                   script_id=script.id,
                                   update_policy=update_policy, note=note),
                        count=count, codes=codes)
        self.tell(data)


class LicenseBatchesHandler(LicenseAdminBase):
    def get(self):
        with session_scope() as session:
            self.admin(session)
            page = max(1, self.get_argument("page", 1, type=int))
            size = min(max(self.get_argument("size", 20, type=int), 1), 200)
            q = session.query(LicenseBatch)
            sid = self.get_argument("script_id", "")
            if sid:
                q = q.filter(LicenseBatch.script_id == int(sid))
            total = q.count()
            items = q.order_by(LicenseBatch.id.desc()).offset(
                (page - 1) * size).limit(size).all()
            rows = []
            for b in items:
                cards = session.query(LicenseCard).filter(
                    LicenseCard.batch_id == b.id)
                rows.append(dict(id=b.id, batch_no=b.batch_no,
                                 script_id=b.script_id,
                                 update_policy=b.update_policy, note=b.note,
                                 group_tag=b.group_tag or b.batch_no,
                                 create_by=b.create_by,
                                 create_time=b.create_time,
                                 cards=cards.count(),
                                 enabled=cards.filter(
                                     LicenseCard.status == "enable").count()))
        self.tell(dict(page=page, size=size, total=total, data=rows))

    def post(self):
        """[M3] 批次切换分组：只影响之后的激活/启动；
        已激活的历史设备请到「分组」页手动迁移。"""
        with session_scope() as session:
            user = self.admin(session)
            batch_id = self.get_argument("batch_id", 0, type=int)
            batch = session.query(LicenseBatch).filter(
                LicenseBatch.id == batch_id).first()
            batch or self.throw(404, "L1004", message="Batch not found")
            tag = (self.get_argument("group_tag", "") or "").strip()
            batch.group_tag = tag or batch.batch_no
            engine.audit(session, user.name, "group_tag",
                         batch_no=batch.batch_no,
                         detail=dict(group_tag=batch.group_tag))
            data = dict(id=batch.id, group_tag=batch.group_tag)
        self.tell(data)


class LicenseCardsHandler(LicenseAdminBase):
    def get(self):
        with session_scope() as session:
            self.admin(session)
            page = max(1, self.get_argument("page", 1, type=int))
            size = min(max(self.get_argument("size", 20, type=int), 1), 200)
            q = session.query(LicenseCard)
            bid = self.get_argument("batch_id", "")
            if bid:
                q = q.filter(LicenseCard.batch_id == int(bid))
            st = self.get_argument("status", "")
            if st:
                q = q.filter(LicenseCard.status == st)
            sid = self.get_argument("script_id", "")
            if sid:
                q = q.join(LicenseBatch,
                           LicenseBatch.id == LicenseCard.batch_id).filter(
                    LicenseBatch.script_id == int(sid))
            # [M3] 关键词：匹配卡密编码 或 绑定设备 dev_id
            kw = (self.get_argument("keyword", "") or "").strip().upper()
            if kw:
                like = "%" + kw + "%"
                q = q.filter(or_(
                    LicenseCard.code.like(like),
                    LicenseCard.id.in_(
                        session.query(LicenseBinding.card_id).filter(
                            LicenseBinding.dev_id.like(like)))))
            total = q.count()
            items = q.order_by(LicenseCard.id.desc()).offset(
                (page - 1) * size).limit(size).all()
            rows = []
            for c in items:
                blist = session.query(LicenseBinding).filter(
                    LicenseBinding.card_id == c.id).all()
                devices = []
                for b in blist:
                    d = session.query(Device).filter(
                        Device.dev_id == b.dev_id).first()
                    devices.append(dict(
                        id=b.id, dev_id=b.dev_id, domain=b.domain,
                        state=b.state, active=b.active,
                        device_state=(d.state if d else None),
                        comment=(d.comment if d else None),
                        blocked=engine.is_blacklisted(session, b.dev_id)))
                # [M3] 卡密所用脚本 + 当前运行版本 + 对应作业（active 绑定优先，缺省=最新版本）
                batch = session.query(LicenseBatch).filter(
                    LicenseBatch.id == c.batch_id).first()
                script = session.query(Script).filter(
                    Script.id == batch.script_id).first() if batch else None
                latest = engine.latest_version(session, script) if script else None
                chosen = next((x for x in blist if x.active), None) \
                    or (blist[0] if blist else None)
                run_version_obj = None
                if chosen and chosen.run_version_id:
                    run_version_obj = session.query(ScriptVersion).filter(
                        ScriptVersion.id == chosen.run_version_id).first()
                if not run_version_obj:
                    run_version_obj = latest
                run_ver = run_version_obj.version if run_version_obj else None
                job_info = None
                if script and run_version_obj and batch:
                    tag = batch.group_tag or batch.batch_no
                    gname = engine.version_group_name(tag, run_version_obj.id)
                    g = session.query(Group).filter(Group.name == gname).first()
                    if g:
                        j = session.query(Job).filter(Job.group_id == g.id).first()
                        if j:
                            job_info = dict(id=j.id, name=j.name)
                rows.append(dict(id=c.id, code=c.code, batch_id=c.batch_id,
                                 status=c.status, expire_at=c.expire_at,
                                 device_limit=c.device_limit, note=c.note,
                                 bound=len(blist), bindings=devices,
                                 quota=engine.unbind_quota_state(c),
                                 script=(dict(id=script.id, name=script.name)
                                         if script else None),
                                 version=run_ver,
                                 latest_version=(latest.version if latest else None),
                                 job=job_info,
                                 create_time=c.create_time))
        blacklist = [dict(dev_id=x.dev_id, reason=x.reason,
                          time=x.create_time) for x in
                     session.query(LicenseBlacklist).order_by(
                         LicenseBlacklist.id.desc()).all()]
        self.tell(dict(page=page, size=size, total=total, data=rows,
                       blacklist=blacklist))

    def post(self):
        """[M3] 设备拉黑/解除：POST {dev_id, blocked(1/0), reason}"""
        with session_scope() as session:
            user = self.admin(session)
            dev_id = (self.get_argument("dev_id", "") or "").strip()
            blocked = self.get_argument("blocked", "1") in ("1", "true", "True")
            reason = self.get_argument("reason", "")
            try:
                engine.set_blacklist(session, dev_id, blocked,
                                     reason=reason, actor=user.name)
            except ValueError as exc:
                self.throw(400, "L4002", message=str(exc))
            data = dict(dev_id=dev_id, blocked=blocked)
        kick_sync()
        self.tell(data)


class LicenseBindingsHandler(LicenseAdminBase):
    def get(self):
        with session_scope() as session:
            self.admin(session)
            page = max(1, self.get_argument("page", 1, type=int))
            size = min(max(self.get_argument("size", 20, type=int), 1), 200)
            q = session.query(LicenseBinding)
            cid = self.get_argument("card_id", "")
            if cid:
                q = q.filter(LicenseBinding.card_id == int(cid))
            dev = self.get_argument("dev_id", "")
            if dev:
                q = q.filter(LicenseBinding.dev_id == dev)
            total = q.count()
            items = q.order_by(LicenseBinding.id.desc()).offset(
                (page - 1) * size).limit(size).all()
            rows = []
            for b in items:
                card = session.query(LicenseCard).filter(
                    LicenseCard.id == b.card_id).first()
                device = session.query(Device).filter(
                    Device.dev_id == b.dev_id).first()
                rows.append(dict(id=b.id, card_id=b.card_id,
                                 code=mask_code(card.code) if card else None,
                                 dev_id=b.dev_id, domain=b.domain,
                                 active=b.active, state=b.state,
                                 device_info=b.device_info,
                                 bind_time=b.bind_time, last_seen=b.last_seen,
                                 device_state=(device.state if device else None),
                                 comment=(device.comment if device else None)))
        self.tell(dict(page=page, size=size, total=total, data=rows))


class LicenseBindingsUnbindHandler(LicenseAdminBase):
    def post(self, binding_id):
        with session_scope() as session:
            user = self.admin(session)
            binding = session.query(LicenseBinding).filter(
                LicenseBinding.id == int(binding_id)).first()
            binding or self.throw(404, "L1003", message="Binding not found")
            device = session.query(Device).filter(
                Device.dev_id == binding.dev_id,
                Device.is_deleted == False).first()
            if device and binding.active:
                engine.stop_device(session, device, revoke=True)
            binding.state = "unbound"
            binding.active = False
            engine.audit(session, user.name, "unbind", dev_id=binding.dev_id,
                         detail=dict(binding=binding.id))
        kick_sync()
        self.tell(dict(total=1))


class LicenseBatchDisableHandler(LicenseAdminBase):
    def post(self, batch_id):
        count = dict(cards=0, devices=0)
        with session_scope() as session:
            user = self.admin(session)
            batch = session.query(LicenseBatch).filter(
                LicenseBatch.id == int(batch_id)).first()
            batch or self.throw(404, "L1004", message="Batch not found")
            cards = session.query(LicenseCard).filter(
                LicenseCard.batch_id == batch.id).all()
            count["cards"] = len(cards)
            for card in cards:
                if card.status == "enable":
                    count["devices"] += engine.kill_card(session, card,
                                                         actor=user.name)
            engine.audit(session, user.name, "disable",
                         batch_no=batch.batch_no, detail=count)
        kick_sync()
        self.tell(count)


class LicenseBatchRestoreHandler(LicenseAdminBase):
    def post(self, batch_id):
        n = 0
        with session_scope() as session:
            user = self.admin(session)
            batch = session.query(LicenseBatch).filter(
                LicenseBatch.id == int(batch_id)).first()
            batch or self.throw(404, "L1004", message="Batch not found")
            cards = session.query(LicenseCard).filter(
                LicenseCard.batch_id == batch.id).all()
            for card in cards:
                if card.status == "disabled":
                    n += engine.restore_card(session, card, actor=user.name)
            engine.audit(session, user.name, "restore",
                         batch_no=batch.batch_no, detail=dict(devices=n))
        kick_sync()
        self.tell(dict(devices=n))


class LicenseCardDisableHandler(LicenseAdminBase):
    def post(self, card_id):
        with session_scope() as session:
            user = self.admin(session)
            card = session.query(LicenseCard).filter(
                LicenseCard.id == int(card_id)).first()
            card or self.throw(404, "L1005", message="Card not found")
            n = engine.kill_card(session, card, actor=user.name)
        kick_sync()
        self.tell(dict(devices=n))


class LicenseCardRestoreHandler(LicenseAdminBase):
    def post(self, card_id):
        with session_scope() as session:
            user = self.admin(session)
            card = session.query(LicenseCard).filter(
                LicenseCard.id == int(card_id)).first()
            card or self.throw(404, "L1005", message="Card not found")
            n = engine.restore_card(session, card, actor=user.name)
        kick_sync()
        self.tell(dict(devices=n))


class LicenseReleaseHandler(LicenseAdminBase):
    def post(self):
        with session_scope() as session:
            user = self.admin(session)
            script_id = self.get_argument("script_id", type=int)
            script = session.query(Script).filter(
                Script.id == script_id, Script.is_deleted == False).first()
            script or self.throw(404, "S4001", message="Script does not exist")
            version = None
            version_id = self.get_argument("version_id", 0, type=int)
            if version_id:
                version = session.query(ScriptVersion).filter(
                    ScriptVersion.id == version_id,
                    ScriptVersion.parent_id == script.id).first()
                version or self.throw(404, "V4101",
                                      message="Script version not exist")
            result = engine.force_update(session, script, actor=user.name,
                                         version=version)
        kick_sync(result.get("job_id"))
        self.tell(result)


class LicenseAuditHandler(LicenseAdminBase):
    def get(self):
        with session_scope() as session:
            self.admin(session)
            page = max(1, self.get_argument("page", 1, type=int))
            size = min(max(self.get_argument("size", 50, type=int), 1), 500)
            q = session.query(LicenseAudit)
            cid = self.get_argument("card_id", "")
            if cid:
                q = q.filter(LicenseAudit.card_id == int(cid))
            dev = self.get_argument("dev_id", "")
            if dev:
                q = q.filter(LicenseAudit.dev_id == dev)
            action = self.get_argument("action", "")
            if action:
                q = q.filter(LicenseAudit.action == action)
            total = q.count()
            items = q.order_by(LicenseAudit.id.desc()).offset(
                (page - 1) * size).limit(size).all()
            rows = [dict(id=a.id, actor=a.actor, action=a.action,
                         card_id=a.card_id, dev_id=a.dev_id,
                         batch_no=a.batch_no, detail=a.detail, time=a.time)
                    for a in items]
        self.tell(dict(page=page, size=size, total=total, data=rows))


# ---------------- 客户端（app 壳） ----------------

class LicenseVerifyHandler(BaseHttpService):
    def post(self):
        code = self.get_argument("code", "")
        dev_id = self.get_argument("dev_id", "")
        dev_id or self.throw(400, "L4001", message="missing dev_id")
        job_id = None
        data = None
        error = None
        try:
            with session_scope() as session:
                # [M3] 防爆破：失败达阈值后锁定一段时间
                engine.verify_guard_check(session, dev_id)
                data, job_id = activate_code(session, code, dev_id,
                                             actor="verify")
        except ValueError as exc:
            error = str(exc)
        if error:
            # 失败计数单独落库（上面的业务事务已回滚）
            try:
                with session_scope() as session:
                    engine.verify_guard_record(session, dev_id)
            except Exception:
                pass
            self.throw(400, "L4002", message=error)
        kick_sync(job_id)
        self.tell(data)


class LicenseSwitchHandler(LicenseClientBase):
    def post(self):
        code = self.get_argument("code", "")
        card_id = self.get_argument("card_id", 0, type=int)
        job_id = None
        data = None
        dev_id = None
        error = None
        try:
            with session_scope() as session:
                binding = self.binding(session)
                dev_id = binding.dev_id
                engine.verify_guard_check(session, dev_id)
                if not code and card_id:
                    # [M3] 快捷切换：按 card_id 切换（无需明文卡密）
                    card = session.query(LicenseCard).filter(
                        LicenseCard.id == card_id).first()
                    if not card:
                        raise ValueError("card_not_exist")
                    own = session.query(LicenseBinding).filter(
                        LicenseBinding.card_id == card.id,
                        LicenseBinding.dev_id == binding.dev_id,
                        LicenseBinding.state != "unbound").first()
                    if not own:
                        raise ValueError("card_not_bound_to_this_device")
                    code = card.code
                data, job_id = activate_code(session, code, binding.dev_id,
                                             actor="switch")
        except ValueError as exc:
            error = str(exc)
        if error:
            if dev_id:
                try:
                    with session_scope() as session:
                        engine.verify_guard_record(session, dev_id)
                except Exception:
                    pass
            self.throw(400, "L4002", message=error)
        kick_sync(job_id)
        self.tell(data)


class LicensePauseHandler(LicenseClientBase):
    """暂停脚本：停发 + 撤销在途任务（app 悬浮窗/卡密页调用；作用于设备当前启用的卡）"""

    def post(self):
        with session_scope() as session:
            binding = self.effective_binding(session, self.binding(session))
            device = self.device(session, binding)
            engine.pause(session, binding, device, actor="app")
        self.tell(dict(state="paused"))


class LicenseResumeHandler(LicenseClientBase):
    """启动脚本：作用于设备当前启用的卡（无启用卡时=token 所属卡）；
    返回 session_token 便于 app 把会话对齐到实际运行的卡。"""

    def post(self):
        job_id = None
        token = ""
        try:
            with session_scope() as session:
                binding = self.effective_binding(session, self.binding(session))
                device = self.device(session, binding)
                job_id = engine.resume(session, binding, device, actor="app")
                token = binding.session_token
        except ValueError as exc:
            self.throw(400, "L4002", message=str(exc))
        kick_sync(job_id)
        self.tell(dict(state="active", session_token=token))


class LicenseStopRunHandler(LicenseClientBase):
    """结束脚本：停发 + 撤销在途任务（state=stopped，可再次启动；作用于设备当前启用的卡）"""

    def post(self):
        with session_scope() as session:
            binding = self.effective_binding(session, self.binding(session))
            device = self.device(session, binding)
            engine.stop_run(session, binding, device, actor="app")
        self.tell(dict(state="stopped"))


class LicenseAppReleaseHandler(LicenseAdminBase):
    """[M3] App 版本管理：GET 查看 / POST 设置（版本号 + 下载地址 + 备注）"""

    def get(self):
        with session_scope() as session:
            self.admin(session)
            row = engine.get_app_release(session)
            data = dict(version=row.version or "", url=row.url or "",
                        note=row.note or "", update_time=row.update_time or 0)
        self.tell(data)

    def post(self):
        with session_scope() as session:
            user = self.admin(session)
            version = self.get_argument("version", "")
            url = self.get_argument("url", "")
            note = self.get_argument("note", "")
            row = engine.set_app_release(session, version, url, note,
                                         actor=user.name)
            data = dict(version=row.version or "", url=row.url or "",
                        note=row.note or "", update_time=row.update_time or 0)
        self.tell(data)


class LicenseCardDevicesHandler(LicenseClientBase):
    """查询卡密的绑定设备列表。
    - 已激活：X-License-Token + card_id（本设备名下卡密）
    - 未激活（换机场景）：code 卡密即凭据 + 可选 dev_id 标记 is_self"""

    def get(self):
        code = (self.get_argument("code", "") or "").strip().upper()
        with session_scope() as session:
            if code:
                check_card_code(code) or self.throw(400, "L4002",
                                                    message="invalid card code")
                card = session.query(LicenseCard).filter(
                    LicenseCard.code == code).first()
                card or self.throw(404, "L1005", message="Card not found")
                from_dev = (self.get_argument("dev_id", "") or "").strip()
            else:
                binding = self.binding(session)
                card_id = self.get_argument("card_id", 0, type=int)
                card = session.query(LicenseCard).filter(
                    LicenseCard.id == card_id).first()
                card or self.throw(404, "L1005", message="Card not found")
                own = session.query(LicenseBinding).filter(
                    LicenseBinding.card_id == card.id,
                    LicenseBinding.dev_id == binding.dev_id,
                    LicenseBinding.state != "unbound").first()
                own or self.throw(403, "L1006",
                                  message="Card not bound to this device")
                from_dev = binding.dev_id
            rows = []
            for b in session.query(LicenseBinding).filter(
                    LicenseBinding.card_id == card.id,
                    LicenseBinding.state != "unbound").order_by(
                    LicenseBinding.id.asc()).all():
                d = session.query(Device).filter(
                    Device.dev_id == b.dev_id).first()
                rows.append(dict(
                    dev_id=b.dev_id, domain=b.domain, state=b.state,
                    active=b.active, is_self=(b.dev_id == from_dev),
                    bind_time=b.bind_time,
                    device_state=(d.state if d else None),
                    device_info=b.device_info or {},
                    comment=(d.comment if d else None)))
            data = dict(card_id=card.id, code=mask_code(card.code),
                        card_status=card.status,
                        device_limit=card.device_limit or 0,
                        devices=rows, quota=engine.unbind_quota_state(card))
        self.tell(data)


class LicenseUnbindHandler(LicenseClientBase):
    """解绑设备（受解绑配额约束）。
    - 已激活：X-License-Token + card_id
    - 未激活（换机场景）：code 卡密即凭据"""

    def post(self):
        code = (self.get_argument("code", "") or "").strip().upper()
        dev_id = self.get_argument("dev_id", "")
        dev_id or self.throw(400, "L4001", message="missing dev_id")
        with session_scope() as session:
            if code:
                check_card_code(code) or self.throw(400, "L4002",
                                                    message="invalid card code")
                card = session.query(LicenseCard).filter(
                    LicenseCard.code == code).first()
                card or self.throw(404, "L1005", message="Card not found")
            else:
                binding = self.binding(session)
                card_id = self.get_argument("card_id", 0, type=int)
                card = session.query(LicenseCard).filter(
                    LicenseCard.id == card_id).first()
                card or self.throw(404, "L1005", message="Card not found")
                own = session.query(LicenseBinding).filter(
                    LicenseBinding.card_id == card.id,
                    LicenseBinding.dev_id == binding.dev_id,
                    LicenseBinding.state != "unbound").first()
                own or self.throw(403, "L1006",
                                  message="Card not bound to this device")
            try:
                quota = engine.unbind(session, card, dev_id, actor="app")
            except ValueError as exc:
                self.throw(400, "L4002", message=str(exc))
        self.tell(dict(state="unbound", dev_id=dev_id, quota=quota))


class LicenseCardUnbindPolicyHandler(LicenseAdminBase):
    """管理端：配置卡密的解绑配额（总次数 / 每日次数 / 间隔秒）"""

    def post(self, card_id):
        with session_scope() as session:
            user = self.admin(session)
            card = session.query(LicenseCard).filter(
                LicenseCard.id == int(card_id)).first()
            card or self.throw(404, "L1005", message="Card not found")
            card.unbind_total_limit = max(0, self.get_argument(
                "unbind_total_limit", card.unbind_total_limit or 0, type=int))
            card.unbind_daily_limit = max(0, self.get_argument(
                "unbind_daily_limit", card.unbind_daily_limit or 0, type=int))
            card.unbind_interval = max(0, self.get_argument(
                "unbind_interval", card.unbind_interval or 0, type=int))
            card.update_time = now()
            engine.audit(session, user.name, "unbind_policy", card=card,
                         detail=dict(total=card.unbind_total_limit,
                                     daily=card.unbind_daily_limit,
                                     interval=card.unbind_interval))
            data = engine.unbind_quota_state(card)
        self.tell(data)


class LicenseMeHandler(LicenseClientBase):
    def get(self):
        with session_scope() as session:
            binding = self.binding(session)
            # [M3] 以"设备当前启用的卡"为准（token 可能属于设备上另一张未启用的卡）
            eff = self.effective_binding(session, binding)
            card = session.query(LicenseCard).filter(
                LicenseCard.id == eff.card_id).first()
            batch = session.query(LicenseBatch).filter(
                LicenseBatch.id == card.batch_id).first()
            script = session.query(Script).filter(
                Script.id == batch.script_id).first()
            device = session.query(Device).filter(
                Device.dev_id == eff.dev_id).first()
            job = None
            if device:
                row = session.query(UserDevice).filter(
                    UserDevice.device_id == device.id,
                    UserDevice.user_id == script.owner_id).first()
                if row and row.group_id:
                    job = session.query(Job).filter(
                        Job.group_id == row.group_id).first()
            available, version = engine.pending_update(session, eff)
            app_row = engine.get_app_release(session)
            data = dict(state=eff.state, active=eff.active,
                        is_self=(eff.id == binding.id),
                        device_state=(device.state if device else None),
                        blacklisted=engine.is_blacklisted(session, eff.dev_id),
                        job=(dict(id=job.id, state=job.state) if job else None),
                        app=dict(version=app_row.version or "",
                                 url=app_row.url or "",
                                 note=app_row.note or ""),
                        card=dict(code=mask_code(card.code),
                                  status=card.status,
                                  expire_at=card.expire_at),
                        script=dict(id=script.id, name=script.name,
                                    description=script.description),
                        batch=dict(batch_no=batch.batch_no,
                                   update_policy=batch.update_policy),
                        update=dict(available=bool(available),
                                    latest=(version.version if version else None)),
                        server_time=now())
        self.tell(data)


class LicenseMyCardsHandler(LicenseClientBase):
    def get(self):
        with session_scope() as session:
            binding = self.binding(session)
            # [M3] 固定排序：按卡密创建时间（+id 兜底），避免切换后顺序跳动
            pairs = session.query(LicenseBinding, LicenseCard).join(
                LicenseCard, LicenseCard.id == LicenseBinding.card_id).filter(
                LicenseBinding.dev_id == binding.dev_id,
                LicenseBinding.state != "unbound").order_by(
                LicenseCard.create_time.asc(), LicenseCard.id.asc()).all()
            rows = []
            for b, card in pairs:
                batch = session.query(LicenseBatch).filter(
                    LicenseBatch.id == card.batch_id).first()
                script = session.query(Script).filter(
                    Script.id == batch.script_id).first()
                rows.append(dict(binding_id=b.id, card_id=card.id,
                                 code=mask_code(card.code),
                                 card_status=card.status,
                                 expire_at=card.expire_at,
                                 create_time=card.create_time,
                                 active=b.active, state=b.state,
                                 script=dict(id=script.id, name=script.name),
                                 update_policy=batch.update_policy))
        self.tell(dict(data=rows))


class LicenseUpdateAcceptHandler(LicenseClientBase):
    def post(self):
        job_id = None
        with session_scope() as session:
            binding = self.effective_binding(session, self.binding(session))
            device = self.device(session, binding)
            ok, job_id = engine.accept_update(session, binding, device)
        kick_sync(job_id)
        self.tell(dict(status="ok" if ok else "noop"))

