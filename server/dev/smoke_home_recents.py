#!encoding=utf8
# smoke: back to home + open the task switcher (recents)
import time

from celery import Task
from celery.worker.request import Request

from lamda.client import Keys
from lamda.executor import BaseTaskExecutor


class FireRPATaskBaseRequest(Request):
    def on_accepted(self, pid, time_accepted):
        super().on_accepted(pid, time_accepted)
    def on_timeout(self, soft, timeout):
        super().on_timeout(soft, timeout)
    def on_failure(self, exc_info, send_failed_event=True, return_ok=False):
        super().on_failure(exc_info, send_failed_event=send_failed_event, return_ok=return_ok)
    def on_success(self, failed__retval__runtime, **kwargs):
        super().on_success(failed__retval__runtime, **kwargs)
    def on_retry(self, exc_info):
        super().on_retry(exc_info)


class FireRPABaseTaskExecutor(Task):
    Request = FireRPATaskBaseRequest
    def before_start(self, task_id, args, kwargs):
        print(f"{task_id} starting with args {args}")
    def on_success(self, retval, task_id, args, kwargs):
        print(f"{task_id} succeeded with result: {retval}")
    def on_failure(self, exc, task_id, args, kwargs, einfo):
        print(f"{task_id} failed: {exc}")
    def after_return(self, status, retval, task_id, args, kwargs, einfo):
        print(f"{task_id} finished with status: {status}")


class FireRPATaskExecutor(BaseTaskExecutor):
    def initialize(self):
        self.logger.info("smoke-initialize v1.0.1")

    def execute(self):
        self.logger.info("smoke: back to home")
        self.device.press_key(Keys.KEY_HOME)
        time.sleep(1.5)
        self.logger.info("smoke: open task switcher")
        self.device.press_key(Keys.KEY_RECENT)
        time.sleep(1.0)
        self.send_message(dict(type="log", name="smoke",
                               data={"message": "home + recents done"}))
        return dict(message="home + recents ok")

    def finalize(self):
        self.logger.info("smoke-finalize")
