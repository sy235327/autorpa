<script setup lang="ts">
import { computed, ref, watch } from "vue";
import {
  Ban,
  Copy,
  Download,
  Loader2,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Smartphone,
} from "lucide-vue-next";
import { Sidebar } from "@/components/dashboard/sidebar";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { FeedbackTip, type FeedbackTipVariant } from "@/components/ui/feedback-tip";
import { Input } from "@/components/ui/input";
import { apiRequest, formatApiError } from "@/lib/api";
import { useHashRouter, useHashSearchParams } from "@/lib/hash-router";
import { useTranslation } from "@/lib/i18n";
import { cn } from "@/lib/utils";

type TabKey = "cards" | "batches" | "audit";

const { t } = useTranslation();
const router = useHashRouter();
const hashSearch = useHashSearchParams();
const scriptId = computed(() => Number(hashSearch.value.get("script_id") || 0));
const scriptName = ref("");
const keyword = ref("");
const pageTitle = computed(() => {
  if (!scriptId.value) return t.value.licensePage.title;
  const name = scriptName.value ? `「${scriptName.value}」` : `#${scriptId.value}`;
  return t.value.licensePage.scriptCardsTitle.replace("{script}", name);
});
const tab = ref<TabKey>("cards");
const loading = ref(false);
const tip = ref<{ id: number; text: string; variant: FeedbackTipVariant } | null>(null);
const refreshTick = ref(0);

const page = ref(1);
const size = ref(20);
const total = ref(0);

const batches = ref<any[]>([]);
const cards = ref<any[]>([]);
const blacklist = ref<any[]>([]);
const audits = ref<any[]>([]);

const cardStatus = ref("");

const createOpen = ref(false);
const creating = ref(false);
const scripts = ref<any[]>([]);
const form = ref({
  script_id: "",
  count: "1",
  update_policy: "force",
  expire_days: "0",
  device_limit: "1",
  unbind_total_limit: "0",
  unbind_daily_limit: "0",
  unbind_interval: "0",
  note: "",
});
const resultOpen = ref(false);
const resultCodes = ref<string[]>([]);

const showTip = (text: string, variant: FeedbackTipVariant = "error") => {
  tip.value = { id: Date.now() + Math.random(), text, variant };
};

const pages = computed(() => Math.max(1, Math.ceil(total.value / size.value)));

const tabs = computed<Array<{ key: TabKey; label: string }>>(() => {
  if (scriptId.value) {
    return [{ key: "cards", label: t.value.licensePage.tabCards }];
  }
  return [
    { key: "cards", label: t.value.licensePage.tabCards },
    { key: "batches", label: t.value.licensePage.tabBatches },
    { key: "audit", label: t.value.licensePage.tabAudit },
  ];
});

function tsText(ts?: number) {
  const n = Number(ts || 0);
  if (!Number.isFinite(n) || n <= 0) return "-";
  return new Date(n * 1000).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}

const load = async () => {
  loading.value = true;
  try {
    const qs = new URLSearchParams({ page: String(page.value), size: String(size.value) });
    if (tab.value === "cards") {
      if (cardStatus.value) qs.set("status", cardStatus.value);
      if (scriptId.value) qs.set("script_id", String(scriptId.value));
      if (keyword.value.trim()) qs.set("keyword", keyword.value.trim());
      const res = await apiRequest<any>(`/api/v1/license/cards?${qs.toString()}`, { cache: "no-store" });
      cards.value = Array.isArray(res.data?.data) ? res.data.data : [];
      blacklist.value = Array.isArray(res.data?.blacklist) ? res.data.blacklist : [];
      total.value = Number(res.data?.total || 0);
    } else if (tab.value === "batches") {
      const res = await apiRequest<any>(`/api/v1/license/batches?${qs.toString()}`, { cache: "no-store" });
      batches.value = Array.isArray(res.data?.data) ? res.data.data : [];
      total.value = Number(res.data?.total || 0);
    } else {
      const res = await apiRequest<any>(`/api/v1/license/audit?${qs.toString()}`, { cache: "no-store" });
      audits.value = Array.isArray(res.data?.data) ? res.data.data : [];
      total.value = Number(res.data?.total || 0);
    }
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.loadFailed));
  } finally {
    loading.value = false;
  }
};

watch([tab, scriptId], () => {
  page.value = 1;
});
watch([tab, page, size, cardStatus, refreshTick, scriptId], () => {
  void load();
}, { immediate: true });

let keywordTimer: number | undefined;
watch(keyword, () => {
  window.clearTimeout(keywordTimer);
  keywordTimer = window.setTimeout(() => {
    page.value = 1;
    void load();
  }, 300);
});

// 脚本上下文：拉取脚本名（标题展示），并强制停留在「卡密」Tab
watch(
  scriptId,
  async (id) => {
    if (!id) {
      scriptName.value = "";
      return;
    }
    tab.value = "cards";
    try {
      const res = await apiRequest<any>("/api/v1/script?page=1&size=200", { cache: "no-store" });
      const list = Array.isArray(res.data?.data) ? res.data.data : [];
      if (list.length) scripts.value = list;
      const hit = list.find((s: any) => Number(s.id) === id);
      scriptName.value = hit?.name || "";
    } catch {
      /* 标题退化为 #id */
    }
  },
  { immediate: true },
);

const openCreate = async () => {
  createOpen.value = true;
  if (scriptId.value) {
    form.value.script_id = String(scriptId.value);
  }
  if (scripts.value.length) return;
  try {
    const res = await apiRequest<any>("/api/v1/script?page=1&size=200", { cache: "no-store" });
    scripts.value = Array.isArray(res.data?.data) ? res.data.data : [];
    if (scriptId.value) {
      form.value.script_id = String(scriptId.value);
    } else if (!form.value.script_id && scripts.value.length) {
      form.value.script_id = String(scripts.value[0].id);
    }
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.loadFailed));
  }
};

const submitCreate = async () => {
  if (creating.value) return;
  if (!form.value.script_id) {
    showTip(t.value.licensePage.formScript);
    return;
  }
  creating.value = true;
  try {
    const body = new URLSearchParams({
      script_id: String(form.value.script_id),
      count: String(Math.max(1, parseInt(form.value.count || "1", 10) || 1)),
      update_policy: form.value.update_policy,
      expire_days: String(Math.max(0, parseInt(form.value.expire_days || "0", 10) || 0)),
      device_limit: String(Math.max(0, parseInt(form.value.device_limit || "0", 10) || 0)),
      unbind_total_limit: String(Math.max(0, parseInt(form.value.unbind_total_limit || "0", 10) || 0)),
      unbind_daily_limit: String(Math.max(0, parseInt(form.value.unbind_daily_limit || "0", 10) || 0)),
      unbind_interval: String(Math.max(0, parseInt(form.value.unbind_interval || "0", 10) || 0)),
      note: form.value.note || "",
    });
    const res = await apiRequest<any>("/api/v1/license/batch/create", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    resultCodes.value = Array.isArray(res.data?.codes) ? res.data.codes : [];
    createOpen.value = false;
    resultOpen.value = true;
    showTip(t.value.licensePage.createDone.replace("{n}", String(resultCodes.value.length)), "success");
    refreshTick.value += 1;
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  } finally {
    creating.value = false;
  }
};

const post = async (url: string) => {
  try {
    await apiRequest(url, { method: "POST" });
    showTip(t.value.licensePage.opSuccess, "success");
    refreshTick.value += 1;
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  }
};

const disableBatch = (b: any) => {
  if (window.confirm(t.value.licensePage.confirmDisableBatch)) void post(`/api/v1/license/batch/${b.id}/disable`);
};
const restoreBatch = (b: any) => void post(`/api/v1/license/batch/${b.id}/restore`);
const disableCard = (c: any) => {
  if (window.confirm(t.value.licensePage.confirmDisableCard)) void post(`/api/v1/license/card/${c.id}/disable`);
};
const restoreCard = (c: any) => void post(`/api/v1/license/card/${c.id}/restore`);
const unbind = (b: any) => {
  if (window.confirm(t.value.licensePage.confirmUnbind)) void post(`/api/v1/license/binding/${b.id}/unbind`);
};

// —— 设备拉黑 / 解除（拉黑后该设备无法使用卡密、启动脚本会被拒绝） ——
const setBlacklist = async (devId: string, blocked: boolean) => {
  if (!devId) return;
  try {
    const body = new URLSearchParams({ dev_id: devId, blocked: blocked ? "1" : "0" });
    await apiRequest("/api/v1/license/cards", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    showTip(t.value.licensePage.opSuccess, "success");
    refreshTick.value += 1;
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  }
};
const toggleBlacklist = (b: any) => {
  const blocked = !b.blocked;
  if (blocked && !window.confirm(t.value.licensePage.confirmBlacklist)) return;
  void setBlacklist(String(b.dev_id || ""), blocked);
};
const unblacklist = (x: any) => void setBlacklist(String(x.dev_id || ""), false);

const copyText = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text);
    showTip(t.value.licensePage.copied, "success");
  } catch {
    showTip(text, "success");
  }
};
const copyAll = () => void copyText(resultCodes.value.join("\n"));
const downloadCsv = () => {
  const blob = new Blob([resultCodes.value.join("\n")], { type: "text/csv" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "license_codes.csv";
  a.click();
  URL.revokeObjectURL(url);
};

const openRemote = (b: any) => {
  const domain = String(b?.domain || "").trim();
  if (!domain) return;
  window.open(`/d/${encodeURIComponent(domain)}/`, "_blank");
};

// —— App 版本管理（版本号 + 下载地址；app 检测到新版本会提示前往下载） ——
const appReleaseOpen = ref(false);
const appReleaseSaving = ref(false);
const appReleaseLoading = ref(false);
const appReleaseForm = ref({ version: "", url: "", note: "" });

const openAppRelease = async () => {
  appReleaseOpen.value = true;
  appReleaseLoading.value = true;
  try {
    const res = await apiRequest<any>("/api/v1/license/app", { cache: "no-store" });
    const d = res.data || {};
    appReleaseForm.value = {
      version: String(d.version || ""),
      url: String(d.url || ""),
      note: String(d.note || ""),
    };
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.loadFailed));
  } finally {
    appReleaseLoading.value = false;
  }
};

const submitAppRelease = async () => {
  if (appReleaseSaving.value) return;
  appReleaseSaving.value = true;
  try {
    const body = new URLSearchParams({
      version: appReleaseForm.value.version.trim(),
      url: appReleaseForm.value.url.trim(),
      note: appReleaseForm.value.note.trim(),
    });
    await apiRequest("/api/v1/license/app", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    appReleaseOpen.value = false;
    showTip(t.value.licensePage.opSuccess, "success");
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  } finally {
    appReleaseSaving.value = false;
  }
};

// —— 批次切换分组（下拉选择现有分组；只影响之后激活/启动，历史设备请在「分组」页手动迁移） ——
const groupTagOpen = ref(false);
const groupTagSaving = ref(false);
const groupTagBatch = ref<any>(null);
const groupTagValue = ref("");
const groupOptions = ref<Array<{ value: string; label: string }>>([]);

const loadGroupOptions = async () => {
  try {
    const res = await apiRequest<any>("/api/v1/group?page=1&size=1000", { cache: "no-store" });
    const list = Array.isArray(res.data?.data) ? res.data.data : [];
    const seen = new Set<string>();
    const opts: Array<{ value: string; label: string }> = [];
    for (const g of list) {
      const name = String(g?.name || "");
      if (!name) continue;
      const value = name.replace(/^license:/, "").replace(/:v\d+$/, "");
      if (seen.has(value)) continue;
      seen.add(value);
      opts.push({ value, label: name });
    }
    groupOptions.value = opts;
  } catch {
    groupOptions.value = [];
  }
};

const openGroupTag = (b: any) => {
  groupTagBatch.value = b;
  groupTagValue.value = String(b.group_tag || b.batch_no || "");
  groupTagOpen.value = true;
  void loadGroupOptions().then(() => {
    const cur = groupTagValue.value;
    if (cur && !groupOptions.value.some((o) => o.value === cur)) {
      groupOptions.value.unshift({ value: cur, label: "license:" + cur });
    }
  });
};

const submitGroupTag = async () => {
  if (groupTagSaving.value || !groupTagBatch.value) return;
  groupTagSaving.value = true;
  try {
    const body = new URLSearchParams({
      batch_id: String(groupTagBatch.value.id),
      group_tag: groupTagValue.value.trim(),
    });
    await apiRequest("/api/v1/license/batches", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    groupTagOpen.value = false;
    showTip(t.value.licensePage.opSuccess, "success");
    refreshTick.value += 1;
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  } finally {
    groupTagSaving.value = false;
  }
};

// —— 解绑策略（管理端：总次数 / 每日次数 / 间隔秒；0=不限） ——
const policyOpen = ref(false);
const policySaving = ref(false);
const policyCard = ref<any>(null);
const policyForm = ref({
  unbind_total_limit: "0",
  unbind_daily_limit: "0",
  unbind_interval: "0",
});

const openPolicy = (c: any) => {
  policyCard.value = c;
  policyForm.value = {
    unbind_total_limit: String(c.quota?.total_limit ?? 0),
    unbind_daily_limit: String(c.quota?.daily_limit ?? 0),
    unbind_interval: String(c.quota?.interval ?? 0),
  };
  policyOpen.value = true;
};

const submitPolicy = async () => {
  if (policySaving.value || !policyCard.value) return;
  policySaving.value = true;
  try {
    const body = new URLSearchParams({
      unbind_total_limit: String(Math.max(0, parseInt(policyForm.value.unbind_total_limit || "0", 10) || 0)),
      unbind_daily_limit: String(Math.max(0, parseInt(policyForm.value.unbind_daily_limit || "0", 10) || 0)),
      unbind_interval: String(Math.max(0, parseInt(policyForm.value.unbind_interval || "0", 10) || 0)),
    });
    await apiRequest(`/api/v1/license/card/${policyCard.value.id}/unbind-policy`, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    policyOpen.value = false;
    showTip(t.value.licensePage.opSuccess, "success");
    refreshTick.value += 1;
  } catch (e) {
    showTip(formatApiError(e, t.value.licensePage.opFailed));
  } finally {
    policySaving.value = false;
  }
};

const shortDev = (id?: string) => String(id || "").slice(0, 8);

const policyText = (p: string) =>
  p === "force" ? t.value.licensePage.policyForce : t.value.licensePage.policyConfirm;
const stateText = (s: string) =>
  s === "enable" ? t.value.licensePage.statusEnable : s === "disabled" ? t.value.licensePage.statusDisabled : s;

// [M3] 激活状态：绑定行的 当前启用/运行态
const bindingStateText = (s: string) =>
  s === "active" ? "运行中"
    : s === "idle" ? "已激活(未启动)"
      : s === "paused" ? "已暂停"
        : s === "stopped" ? "已结束"
          : s === "kicked" ? "已停用"
            : s || "-";

const cardActivation = (c: any): { text: string; cls: string } => {
  const binds = Array.isArray(c.bindings) ? c.bindings : [];
  if (!binds.length) {
    return { text: t.value.licensePage.activationNone, cls: "text-gray-400" };
  }
  const cur = binds.find((b: any) => b.active) || binds.find((b: any) => String(b.state || "") === "active");
  if (cur) {
    return {
      text: t.value.licensePage.activationActive + " · " + bindingStateText(String(cur.state || "")),
      cls: "text-green-600",
    };
  }
  return { text: t.value.licensePage.activationBound, cls: "text-gray-500" };
};
</script>

<template>
  <div class="flex h-screen bg-[#f5f5f7]">
    <Sidebar />
    <div class="flex min-w-0 flex-1 flex-col overflow-hidden lg:ml-[220px]">
      <div class="h-14 shrink-0 border-b border-gray-100 bg-white px-5 py-2.5">
        <div class="flex items-center justify-between">
          <div class="flex items-center gap-2">
            <h1 class="text-base font-semibold">{{ pageTitle }}</h1>
            <Button
              v-if="scriptId"
              variant="ghost"
              size="sm"
              class="h-7 px-2 text-xs text-gray-500"
              @click="router.push('/license')"
            >
              {{ t.licensePage.viewAllCards }}
            </Button>
          </div>
          <div class="flex items-center gap-2">
            <FeedbackTip
              v-if="tip"
              :key="tip.id"
              :message="tip.text"
              :variant="tip.variant"
              truncate
              class="max-w-[360px]"
            />
            <Button variant="outline" size="sm" class="h-8 gap-1.5 text-xs" @click="openAppRelease">
              <Smartphone class="h-3.5 w-3.5" />
              {{ t.licensePage.appRelease }}
            </Button>
            <Button size="sm" class="h-8 gap-1.5 text-xs" @click="openCreate">
              <Plus class="h-3.5 w-3.5" />
              {{ t.licensePage.generate }}
            </Button>
            <Button
              variant="outline"
              size="sm"
              class="h-8 gap-1.5 text-xs"
              :disabled="loading"
              @click="refreshTick += 1"
            >
              <RefreshCw :class="cn('h-3.5 w-3.5', loading && 'animate-spin')" />
              {{ t.licensePage.refresh }}
            </Button>
          </div>
        </div>
      </div>

      <div class="flex min-h-0 flex-1 flex-col overflow-hidden p-3">
        <div class="flex min-h-0 flex-1 flex-col overflow-hidden rounded-lg border border-gray-100 bg-white shadow-sm">
          <div class="shrink-0 border-b border-gray-100 px-4 py-2">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div class="flex items-center gap-1">
                <button
                  v-for="item in tabs"
                  :key="item.key"
                  type="button"
                  :class="
                    cn(
                      'rounded-md px-3 py-1.5 text-xs transition-colors',
                      tab === item.key
                        ? 'bg-orange-500/10 font-medium text-orange-600'
                        : 'text-gray-500 hover:bg-gray-50',
                    )
                  "
                  @click="tab = item.key; page = 1"
                >
                  {{ item.label }}
                </button>
              </div>
              <div class="flex items-center gap-2">
                <div v-if="tab === 'cards'" class="relative">
                  <Search class="pointer-events-none absolute left-2 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-gray-400" />
                  <Input
                    v-model="keyword"
                    class="h-8 w-[220px] pl-7 text-xs"
                    :placeholder="t.licensePage.searchPlaceholder"
                  />
                </div>
                <select
                  v-if="tab === 'cards'"
                  v-model="cardStatus"
                  class="h-8 rounded-md border border-gray-200 bg-white px-2 text-xs text-gray-600"
                  @change="page = 1"
                >
                  <option value="">{{ t.licensePage.all }}</option>
                  <option value="enable">{{ t.licensePage.statusEnable }}</option>
                  <option value="disabled">{{ t.licensePage.statusDisabled }}</option>
                </select>
                <Badge variant="secondary" class="h-5 rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-500 tabular-nums">
                  {{ total.toLocaleString() }}
                </Badge>
              </div>
            </div>
          </div>

          <div
            v-if="tab === 'cards' && blacklist.length"
            class="flex flex-wrap items-center gap-1 border-b border-red-100 bg-red-50/60 px-4 py-1.5 text-[11px] text-red-600"
          >
            <span class="font-medium">{{ t.licensePage.blacklistLabel }}：</span>
            <span
              v-for="x in blacklist"
              :key="x.dev_id"
              class="inline-flex items-center gap-1 rounded bg-white px-1.5 py-0.5 font-mono"
            >
              {{ shortDev(x.dev_id) }}
              <button type="button" class="font-sans text-green-600 hover:underline" @click="unblacklist(x)">
                {{ t.licensePage.unblock }}
              </button>
            </span>
          </div>

          <div class="min-h-0 flex-1 overflow-auto">
            <div v-if="loading" class="flex items-center justify-center py-16 text-xs text-gray-400">
              <Loader2 class="mr-2 h-4 w-4 animate-spin" />
              {{ t.common.loading }}
            </div>

            <table v-else-if="tab === 'cards'" class="w-full text-xs">
              <thead class="sticky top-0 bg-gray-50 text-left text-gray-500">
                <tr>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colCode }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colStatus }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colActivation }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colDevice }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colScriptVersion }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colExpire }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colUnbind }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colCreateTime }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colActions }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in cards" :key="c.id" class="border-t border-gray-50 hover:bg-gray-50/50">
                  <td class="px-3 py-2 font-mono text-gray-800">
                    <span class="inline-flex items-center gap-1.5">
                      {{ c.code }}
                      <button type="button" class="text-gray-400 hover:text-gray-600" @click="copyText(c.code)">
                        <Copy class="h-3 w-3" />
                      </button>
                    </span>
                  </td>
                  <td class="px-3 py-2">
                    <span :class="c.status === 'enable' ? 'text-green-600' : 'text-red-500'">
                      {{ stateText(c.status) }}
                    </span>
                  </td>
                  <td class="px-3 py-2">
                    <span :class="cardActivation(c).cls">{{ cardActivation(c).text }}</span>
                  </td>
                  <td class="px-3 py-2">
                    <div v-if="c.bindings && c.bindings.length" class="flex flex-wrap items-center gap-1.5">
                      <span v-for="b in c.bindings" :key="b.dev_id" class="inline-flex items-center gap-0.5">
                        <button
                          type="button"
                          class="inline-flex items-center gap-1 rounded bg-gray-100 px-1.5 py-0.5 font-mono text-[10px] text-gray-600 transition-colors hover:bg-orange-50 hover:text-orange-600"
                          :title="`${b.dev_id}\n${b.device_state || '-'} / ${b.state || '-'}`"
                          @click="openRemote(b)"
                        >
                          <span
                            class="inline-block h-1.5 w-1.5 rounded-full"
                            :class="b.device_state === 'online' ? 'bg-green-500' : 'bg-gray-300'"
                          />
                          {{ b.domain || shortDev(b.dev_id) }}
                        </button>
                        <span v-if="b.blocked" class="rounded bg-red-50 px-1 py-0.5 text-[10px] text-red-500">
                          {{ t.licensePage.blockedTag }}
                        </span>
                        <button
                          type="button"
                          class="text-[10px] text-red-400 hover:text-red-600 hover:underline"
                          @click="unbind(b)"
                        >
                          {{ t.licensePage.unbind }}
                        </button>
                        <button
                          type="button"
                          class="text-[10px] hover:underline"
                          :class="b.blocked ? 'text-green-600 hover:text-green-700' : 'text-gray-400 hover:text-gray-600'"
                          @click="toggleBlacklist(b)"
                        >
                          {{ b.blocked ? t.licensePage.unblock : t.licensePage.blacklist }}
                        </button>
                      </span>
                      <span class="text-gray-300"> {{ c.bound }}/{{ c.device_limit || "∞" }} </span>
                    </div>
                    <span v-else class="text-gray-300">-</span>
                  </td>
                  <td class="px-3 py-2 text-gray-500">
                    <span v-if="c.script">#{{ c.script.id }} {{ c.script.name }}</span>
                    <span v-else>-</span>
                    <div class="text-[11px] text-gray-400">
                      v{{ c.version || "-" }}
                      <span v-if="c.version && c.latest_version && c.version !== c.latest_version" class="text-orange-500">
                        （最新 v{{ c.latest_version }}）
                      </span>
                      <button
                        v-if="c.job"
                        type="button"
                        class="ml-1 text-blue-600 hover:underline"
                        @click="router.push(`/jobs/${c.job.id}`)"
                      >
                        → {{ t.licensePage.jobLabel }} #{{ c.job.id }}
                      </button>
                    </div>
                  </td>
                  <td class="px-3 py-2 text-gray-500">{{ c.expire_at ? tsText(c.expire_at) : t.licensePage.permanent }}</td>
                  <td class="px-3 py-2 text-gray-500">
                    <span v-if="c.quota" class="whitespace-nowrap">
                      {{ t.licensePage.quotaTotal }} {{ c.quota.total_used }}/{{ c.quota.total_limit || "∞" }}
                      · {{ t.licensePage.quotaDaily }} {{ c.quota.day_used }}/{{ c.quota.daily_limit || "∞" }}
                      <span v-if="c.quota.interval"> · {{ c.quota.interval }}s</span>
                    </span>
                    <span v-else>-</span>
                  </td>
                  <td class="px-3 py-2 text-gray-500">{{ tsText(c.create_time) }}</td>
                  <td class="px-3 py-2">
                    <div class="flex items-center gap-1">
                      <Button v-if="c.status === 'enable'" variant="outline" size="sm" class="h-6 gap-1 px-2 text-[11px]" @click="disableCard(c)">
                        <Ban class="h-3 w-3" />{{ t.licensePage.disable }}
                      </Button>
                      <Button v-else variant="outline" size="sm" class="h-6 gap-1 px-2 text-[11px]" @click="restoreCard(c)">
                        <RotateCcw class="h-3 w-3" />{{ t.licensePage.restore }}
                      </Button>
                      <Button variant="ghost" size="sm" class="h-6 gap-1 px-2 text-[11px]" @click="openPolicy(c)">
                        {{ t.licensePage.unbindPolicy }}
                      </Button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!cards.length">
                  <td colspan="9" class="px-3 py-16 text-center text-gray-400">{{ t.licensePage.empty }}</td>
                </tr>
              </tbody>
            </table>

            <table v-else-if="tab === 'batches'" class="w-full text-xs">
              <thead class="sticky top-0 bg-gray-50 text-left text-gray-500">
                <tr>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colBatchNo }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colScript }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colGroupTag }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colCount }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colEnabled }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colNote }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colCreateTime }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colActions }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="b in batches" :key="b.id" class="border-t border-gray-50 hover:bg-gray-50/50">
                  <td class="px-3 py-2 font-mono text-gray-800">{{ b.batch_no }}</td>
                  <td class="px-3 py-2 text-gray-500">#{{ b.script_id }}</td>
                  <td class="px-3 py-2 text-gray-700">
                    <span class="font-mono text-[11px]">{{ b.group_tag || b.batch_no }}</span>
                    <Button variant="ghost" size="sm" class="ml-1 h-6 px-1.5 text-[11px]" @click="openGroupTag(b)">
                      {{ t.licensePage.groupTagSwitch }}
                    </Button>
                  </td>
                  <td class="px-3 py-2 text-gray-500">{{ b.cards }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ b.enabled }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ b.note || "-" }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ tsText(b.create_time) }}</td>
                  <td class="px-3 py-2">
                    <div class="flex items-center gap-1">
                      <Button variant="outline" size="sm" class="h-6 gap-1 px-2 text-[11px]" @click="disableBatch(b)">
                        <Ban class="h-3 w-3" />{{ t.licensePage.disableBatch }}
                      </Button>
                      <Button variant="outline" size="sm" class="h-6 gap-1 px-2 text-[11px]" @click="restoreBatch(b)">
                        <RotateCcw class="h-3 w-3" />{{ t.licensePage.restoreBatch }}
                      </Button>
                    </div>
                  </td>
                </tr>
                <tr v-if="!batches.length">
                  <td colspan="8" class="px-3 py-16 text-center text-gray-400">{{ t.licensePage.empty }}</td>
                </tr>
              </tbody>
            </table>

            <table v-else class="w-full text-xs">
              <thead class="sticky top-0 bg-gray-50 text-left text-gray-500">
                <tr>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colTime }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colActor }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colAction }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colCard }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colDevId }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colBatch }}</th>
                  <th class="px-3 py-2 font-medium">{{ t.licensePage.colDetail }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="a in audits" :key="a.id" class="border-t border-gray-50 hover:bg-gray-50/50">
                  <td class="px-3 py-2 text-gray-500">{{ tsText(a.time) }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ a.actor || "-" }}</td>
                  <td class="px-3 py-2 text-gray-800">{{ a.action }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ a.card_id ?? "-" }}</td>
                  <td class="px-3 py-2 font-mono text-gray-500">{{ a.dev_id ? String(a.dev_id).slice(0, 12) + "…" : "-" }}</td>
                  <td class="px-3 py-2 text-gray-500">{{ a.batch_no || "-" }}</td>
                  <td class="px-3 py-2 text-gray-400">{{ JSON.stringify(a.detail || {}).slice(0, 80) }}</td>
                </tr>
                <tr v-if="!audits.length">
                  <td colspan="7" class="px-3 py-16 text-center text-gray-400">{{ t.licensePage.empty }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="flex shrink-0 items-center justify-between border-t border-gray-100 px-4 py-2 text-xs text-gray-500">
            <span>{{ page }} / {{ pages }}</span>
            <div class="flex items-center gap-2">
              <Button variant="outline" size="sm" class="h-7 px-2 text-xs" :disabled="page <= 1" @click="page -= 1">
                {{ t.licensePage.prev }}
              </Button>
              <Button variant="outline" size="sm" class="h-7 px-2 text-xs" :disabled="page >= pages" @click="page += 1">
                {{ t.licensePage.next }}
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <Dialog v-model:open="createOpen">
      <DialogContent class="max-w-[520px]">
        <DialogHeader>
          <DialogTitle>{{ t.licensePage.createTitle }}</DialogTitle>
        </DialogHeader>
        <div class="space-y-3 py-2 text-xs">
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.formScript }}</p>
            <select
              v-if="!scriptId"
              v-model="form.script_id"
              class="h-8 w-full rounded-md border border-gray-200 bg-white px-2 text-xs text-gray-700"
            >
              <option v-for="s in scripts" :key="s.id" :value="String(s.id)">#{{ s.id }} {{ s.name }}</option>
            </select>
            <p v-else class="rounded-md bg-gray-50 px-2 py-1.5 text-xs text-gray-700">
              #{{ scriptId }} {{ scriptName || "" }}
            </p>
          </div>
          <div class="grid grid-cols-2 gap-3">
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formCount }}</p>
              <Input v-model="form.count" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formExpireDays }}</p>
              <Input v-model="form.expire_days" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formDeviceLimit }}</p>
              <Input v-model="form.device_limit" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindTotal }}</p>
              <Input v-model="form.unbind_total_limit" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindDaily }}</p>
              <Input v-model="form.unbind_daily_limit" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindInterval }}</p>
              <Input v-model="form.unbind_interval" class="h-8 text-xs" />
            </div>
          </div>
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.formNote }}</p>
            <Input v-model="form.note" class="h-8 text-xs" />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" size="sm" class="h-8 text-xs" @click="createOpen = false">
            {{ t.licensePage.cancel }}
          </Button>
          <Button size="sm" class="h-8 gap-1.5 text-xs" :disabled="creating" @click="submitCreate">
            <Loader2 v-if="creating" class="h-3.5 w-3.5 animate-spin" />
            {{ t.licensePage.submit }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="appReleaseOpen">
      <DialogContent class="max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{{ t.licensePage.appReleaseTitle }}</DialogTitle>
        </DialogHeader>
        <div class="space-y-3 py-2 text-xs">
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.formAppVersion }}</p>
            <Input v-model="appReleaseForm.version" class="h-8 text-xs" placeholder="1.0.0" />
          </div>
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.formAppUrl }}</p>
            <Input v-model="appReleaseForm.url" class="h-8 text-xs" placeholder="https://..." />
          </div>
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.formAppNote }}</p>
            <Input v-model="appReleaseForm.note" class="h-8 text-xs" />
          </div>
          <p class="rounded-md bg-orange-50 px-2 py-2 text-[11px] leading-5 text-orange-700">
            {{ t.licensePage.appReleaseHint }}
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" size="sm" class="h-8 text-xs" @click="appReleaseOpen = false">
            {{ t.licensePage.cancel }}
          </Button>
          <Button size="sm" class="h-8 gap-1.5 text-xs" :disabled="appReleaseSaving || appReleaseLoading" @click="submitAppRelease">
            <Loader2 v-if="appReleaseSaving" class="h-3.5 w-3.5 animate-spin" />
            {{ t.licensePage.submit }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="groupTagOpen">
      <DialogContent class="max-w-[440px]">
        <DialogHeader>
          <DialogTitle>{{ t.licensePage.groupTagTitle }}</DialogTitle>
        </DialogHeader>
        <div class="space-y-3 py-2 text-xs">
          <p class="font-mono text-gray-700">{{ groupTagBatch?.batch_no }}</p>
          <div class="space-y-1">
            <p class="text-gray-500">{{ t.licensePage.colGroupTag }}</p>
            <select
              v-model="groupTagValue"
              class="h-8 w-full rounded-md border border-gray-200 bg-white px-2 text-xs text-gray-700"
            >
              <option v-for="g in groupOptions" :key="g.value" :value="g.value">{{ g.label }}</option>
            </select>
          </div>
          <p class="rounded-md bg-orange-50 px-2 py-2 text-[11px] leading-5 text-orange-700">
            {{ t.licensePage.groupTagHint }}
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" size="sm" class="h-8 text-xs" @click="groupTagOpen = false">
            {{ t.licensePage.cancel }}
          </Button>
          <Button size="sm" class="h-8 gap-1.5 text-xs" :disabled="groupTagSaving" @click="submitGroupTag">
            <Loader2 v-if="groupTagSaving" class="h-3.5 w-3.5 animate-spin" />
            {{ t.licensePage.submit }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="policyOpen">
      <DialogContent class="max-w-[440px]">
        <DialogHeader>
          <DialogTitle>{{ t.licensePage.unbindPolicyTitle }}</DialogTitle>
        </DialogHeader>
        <div class="space-y-3 py-2 text-xs">
          <p class="font-mono text-gray-700">{{ policyCard?.code }}</p>
          <div class="grid grid-cols-3 gap-3">
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindTotal }}</p>
              <Input v-model="policyForm.unbind_total_limit" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindDaily }}</p>
              <Input v-model="policyForm.unbind_daily_limit" class="h-8 text-xs" />
            </div>
            <div class="space-y-1">
              <p class="text-gray-500">{{ t.licensePage.formUnbindInterval }}</p>
              <Input v-model="policyForm.unbind_interval" class="h-8 text-xs" />
            </div>
          </div>
          <p class="rounded-md bg-orange-50 px-2 py-2 text-[11px] leading-5 text-orange-700">
            {{ t.licensePage.unbindPolicyHint }}
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" size="sm" class="h-8 text-xs" @click="policyOpen = false">
            {{ t.licensePage.cancel }}
          </Button>
          <Button size="sm" class="h-8 gap-1.5 text-xs" :disabled="policySaving" @click="submitPolicy">
            <Loader2 v-if="policySaving" class="h-3.5 w-3.5 animate-spin" />
            {{ t.licensePage.submit }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>

    <Dialog v-model:open="resultOpen">
      <DialogContent class="max-w-[520px]">
        <DialogHeader>
          <DialogTitle>{{ t.licensePage.generatedTitle }}</DialogTitle>
        </DialogHeader>
        <div class="max-h-[320px] space-y-1 overflow-auto rounded-md bg-gray-50 p-3 font-mono text-xs text-gray-700">
          <p v-for="c in resultCodes" :key="c">{{ c }}</p>
        </div>
        <DialogFooter>
          <Button variant="outline" size="sm" class="h-8 gap-1.5 text-xs" @click="copyAll">
            <Copy class="h-3.5 w-3.5" />{{ t.licensePage.copyAll }}
          </Button>
          <Button variant="outline" size="sm" class="h-8 gap-1.5 text-xs" @click="downloadCsv">
            <Download class="h-3.5 w-3.5" />{{ t.licensePage.downloadCsv }}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
