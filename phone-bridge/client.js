/**
 * phone-bridge · 设置页「语音通道」（浏览器半端，**自注册脚本，不是 ESM**）
 * ============================================================================
 * 进「设置」的写法照 dsh-bili-scroll（同机已验证）：slots.inject('settings.section', …)
 * label 就是左侧导航那一项「语音通道」。
 *
 * 这一页只做四件事（规格 B 段）：
 *   通道   状态 / 端口 / 启停（托管进程在宿主侧，面板只发固定动作）
 *   配对   点「生成」→ POST /api/phone-bridge/pair → 6 位码 + 5 分钟倒计时
 *   设备   GET /api/phone-bridge/devices → 标识 / 首次配对 / 最近活跃
 *   语音   提供商 + API key（key 只回掩码，实际写进 channel-server\.env）
 *   日志   channel-server 最后十几行
 *
 * 约束：所有请求 same-origin（credentials: 'same-origin'），不读别的插件 DOM，
 * 不把 key 放进 URL、日志或页面标题。
 * ============================================================================
 */
window.__ModuleLoader__.load({
  id: '@local/phone-bridge',
  factory: (require) => {
    const React = require('react');
    const h = React.createElement;

    const API = '/api/phone-bridge';
    // 默认提供商改成 DeepSeek；Edge / 本地排在后面并保留「未实现」标注
    //（现在只写进 .env 的 CHANNEL_TTS_PROVIDER，不改变实际播报链路）。
    const DEFAULT_PROVIDER = 'deepseek';
    const PROVIDERS = [
      ['deepseek', 'DeepSeek（默认）'],
      ['edge', 'Edge（未实现）'],
      ['local', '本地（未实现）'],
    ];

    /** 把 POST /pair 的响应收成面板要显示的形状；拿不到完整的码/到期时间就回 null。 */
    function pairFromResponse(r) {
      if (!r || r.ok === false) return null;
      const code = typeof r.code === 'string' ? r.code.trim() : '';
      const expiresAt = Number(r.expiresAt);
      if (!code || !Number.isFinite(expiresAt)) return null;
      const ttlMs = Number(r.ttlMs);
      return {
        active: true,
        code,
        expiresAt,
        ttlMs: Number.isFinite(ttlMs) ? ttlMs : Math.max(0, expiresAt - Date.now()),
      };
    }

    /**
     * 轮询回来时合并配对状态（纯函数，selfcheck 直接测）：
     *   - 本地有未过期的码，轮询同码（或轮询没带码）⇒ 保留本地，**别把刚生成的码冲掉**；
     *   - 轮询带了一个有效的新码 ⇒ 采纳新码（例如通道重启后另生成了一个）；
     *   - 两边都过期/没有 ⇒ null（调用方据此显示「已过期」或「还没有」）。
     */
    function mergePair(current, polled, now) {
      const at = Number(now) || Date.now();
      const cur = current && Number(current.expiresAt) > at ? current : null;
      const pol = polled && polled.active === true && Number(polled.expiresAt) > at ? polled : null;
      if (cur && !pol) return cur;
      if (cur && pol) {
        if (!pol.code || pol.code === cur.code) return cur;
        return pol;
      }
      return pol;
    }

    const asJson = async (res) => {
      if (res.ok) return res.json();
      // 400 的中文原因在响应体里：尽量把它带进错误消息，别只留一个状态码
      let detail = '';
      try {
        const data = await res.json();
        if (data && (data.message || data.error)) detail = `：${data.message || data.error}`;
      } catch {
        /* 不是 JSON 就算了 */
      }
      const why =
        res.status === 401 ? '（未登录 / 没权限）'
          : res.status === 403 ? '（被宿主同源校验拦了）'
            : res.status === 404 ? '（接口不存在：插件没重启或宿主没有 webServer）'
              : '';
      throw new Error(`HTTP ${res.status}${detail}${why}`);
    };
    const jget = async (p) => asJson(await fetch(API + p, { credentials: 'same-origin' }));
    const jpost = async (p, body) =>
      asJson(
        await fetch(API + p, {
          method: 'POST',
          credentials: 'same-origin',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body || {}),
        }),
      );

    const CSS = `
[data-pb-page]{display:flex;flex-direction:column;gap:16px;padding:2px 2px 8px;max-width:720px;}
[data-pb-sec]{display:flex;flex-direction:column;gap:8px;}
[data-pb-h]{font-size:11px;font-weight:600;letter-spacing:.04em;color:var(--dsw-alias-label-tertiary,#9aa0a6);}
[data-pb-row]{display:flex;align-items:center;gap:10px;flex-wrap:wrap;}
[data-pb-grow]{flex:1;min-width:0;}
[data-pb-label]{font-size:13px;}
[data-pb-note]{font-size:11px;line-height:16px;color:var(--dsw-alias-label-tertiary,#9aa0a6);}
[data-pb-input]{width:100%;box-sizing:border-box;padding:7px 9px;border-radius:8px;font-size:12px;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.14));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.12));color:inherit;font-family:inherit;}
[data-pb-input]:focus{outline:none;border-color:#3b82f6;}
select[data-pb-input]{color-scheme:light dark;}
select[data-pb-input] option{background-color:var(--dsw-alias-bg-base,#fff);color:var(--dsw-alias-label-primary,#1a1a1a);}
[data-pb-btn]{padding:7px 12px;border-radius:8px;font-size:12px;cursor:pointer;font-family:inherit;white-space:nowrap;
  border:1px solid var(--dsw-alias-bg-layer-3,rgba(255,255,255,.16));
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.14));color:inherit;}
[data-pb-btn]:hover{background:var(--dsw-alias-interactive-bg-hover,rgba(128,128,128,.24));}
[data-pb-btn][data-primary]{background:#3b82f6;border-color:#3b82f6;color:#fff;}
[data-pb-btn][data-danger]{color:#f87171;}
[data-pb-btn][disabled]{opacity:.5;cursor:default;}
[data-pb-msg]{font-size:11px;line-height:16px;padding:7px 9px;border-radius:8px;word-break:break-word;
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.12));color:var(--dsw-alias-label-secondary,#c9ccd1);}
[data-pb-msg][data-good]{color:#22c55e;}
[data-pb-msg][data-warn]{color:#f59e0b;}
[data-pb-msg][data-bad]{color:#f87171;}
[data-pb-badge]{font-size:11px;padding:2px 7px;border-radius:6px;white-space:nowrap;
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.14));color:var(--dsw-alias-label-secondary,#c9ccd1);}
[data-pb-badge][data-good]{color:#22c55e;}
[data-pb-badge][data-warn]{color:#f59e0b;}
[data-pb-kv]{font-size:11px;line-height:18px;color:var(--dsw-alias-label-tertiary,#9aa0a6);word-break:break-all;}
[data-pb-code]{font-size:30px;line-height:38px;font-weight:700;letter-spacing:.28em;font-variant-numeric:tabular-nums;}
[data-pb-k]{font-size:11px;color:var(--dsw-alias-label-tertiary,#9aa0a6);}
[data-pb-dev]{display:flex;flex-direction:column;gap:2px;padding:7px 9px;border-radius:8px;
  background:var(--dsw-alias-bg-layer-4,rgba(128,128,128,.10));}
[data-pb-dev-id]{font-size:12px;word-break:break-all;color:var(--dsw-alias-label-primary,inherit);}
[data-pb-dev-meta]{font-size:11px;color:var(--dsw-alias-label-tertiary,#9aa0a6);display:flex;gap:14px;flex-wrap:wrap;}
[data-pb-pre]{margin:0;padding:8px 9px;border-radius:8px;max-height:220px;overflow:auto;font-size:11px;
  line-height:15px;white-space:pre-wrap;word-break:break-all;
  background:var(--dsw-alias-bg-layer-4,rgba(0,0,0,.25));color:var(--dsw-alias-label-secondary,#c9ccd1);}
`;

    function fmtTime(ms) {
      if (!ms) return '—';
      try {
        return new Date(ms).toLocaleString('zh-CN', { hour12: false });
      } catch {
        return '—';
      }
    }

    function useNow(active) {
      const [now, setNow] = React.useState(Date.now());
      React.useEffect(() => {
        if (!active) return undefined;
        const t = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(t);
      }, [active]);
      return now;
    }

    function useStatus(intervalMs) {
      const [st, setSt] = React.useState(null);
      const [fails, setFails] = React.useState(0);
      React.useEffect(() => {
        let alive = true;
        const tick = async () => {
          try {
            const d = await jget('/status');
            if (!alive) return;
            if (d && d.ok) {
              setSt(d);
              setFails(0);
            } else {
              setFails((n) => n + 1);
            }
          } catch {
            if (alive) setFails((n) => n + 1);
          }
        };
        tick();
        const t = setInterval(tick, intervalMs || 4000);
        return () => {
          alive = false;
          clearInterval(t);
        };
      }, [intervalMs]);
      return [st, setSt, fails];
    }

    function Badge({ tone, children }) {
      const attr = {};
      if (tone) attr['data-' + tone] = '';
      return h('span', { 'data-pb-badge': '', ...attr }, children);
    }

    function Section({ title, children }) {
      return h('div', { 'data-pb-sec': '' }, h('div', { 'data-pb-h': '' }, title), children);
    }

    function Panel() {
      const [st, setSt, fails] = useStatus(4000);
      const [busy, setBusy] = React.useState('');
      const [msg, setMsg] = React.useState('');
      const [msgTone, setMsgTone] = React.useState('');
      const [providerDraft, setProviderDraft] = React.useState(DEFAULT_PROVIDER);
      const [providerEdited, setProviderEdited] = React.useState(false);
      const [keyDraft, setKeyDraft] = React.useState('');
      const [pair, setPair] = React.useState(null);
      const [pairExpired, setPairExpired] = React.useState(false);
      const [devices, setDevices] = React.useState(null);
      const [devicesErr, setDevicesErr] = React.useState('');
      const [sessionDraft, setSessionDraft] = React.useState(null);

      const say = (text, tone) => {
        setMsg(text);
        setMsgTone(tone || '');
      };

      const applyStatus = (patch) => setSt((old) => (old ? { ...old, ...patch } : old));

      // 语音提供商：默认 DeepSeek；轮询回来的值只填「用户还没动过」的草稿
      const voice = (st && st.voice) || null;
      React.useEffect(() => {
        if (voice && !providerEdited) setProviderDraft(voice.provider || DEFAULT_PROVIDER);
      }, [voice && voice.provider, providerEdited]);

      // 投递会话：同上，只填还没动过的草稿
      const session = (st && st.session) || null;
      React.useEffect(() => {
        if (session && sessionDraft === null) setSessionDraft(session.sessionId || '');
      }, [session && session.sessionId]);

      const loadDevices = async () => {
        setBusy('devices');
        setDevicesErr('');
        try {
          const r = await jget('/devices');
          if (!r || !r.ok) {
            setDevices([]);
            setDevicesErr((r && (r.message || r.hint || r.error)) || '读不到设备列表');
            return;
          }
          setDevices(r.devices || []);
        } catch (e) {
          setDevices([]);
          setDevicesErr(String((e && e.message) || e));
        } finally {
          setBusy('');
        }
      };
      React.useEffect(() => {
        loadDevices();
      }, []);

      // 配对：轮询只做合并；本地刚点「生成」拿到的码不会被下一次轮询冲掉
      React.useEffect(() => {
        const polled = st && st.pair;
        if (!polled || polled.active !== true) return;
        if (!(Number(polled.expiresAt) > Date.now())) return;
        setPair((prev) => mergePair(prev, polled, Date.now()));
        setPairExpired(false);
      }, [st && st.pair && st.pair.active, st && st.pair && st.pair.code, st && st.pair && st.pair.expiresAt]);

      const now = useNow(Boolean(pair));
      const pairActive = Boolean(pair && pair.active === true && Number(pair.expiresAt) > now);
      React.useEffect(() => {
        if (pair && Number(pair.expiresAt) <= now) {
          setPair(null); // 过期就清空：不再显示码与倒计时
          setPairExpired(true); // 但保留一条「已过期，重新生成」的提示
        }
      }, [now, pair]);

      const channel = (st && st.channel) || null;
      const running = Boolean(channel && channel.running) || Boolean(channel && channel.reachable);
      const managed = Boolean(channel && channel.managed);
      const port = (channel && channel.port) || 8788;
      const addresses = st && Array.isArray(st.addresses) ? st.addresses : [];

      const copyAddress = async (url) => {
        try {
          await navigator.clipboard.writeText(url);
          say(`已复制：${url}`, 'good');
        } catch {
          say(`复制失败，地址是：${url}`, 'warn');
        }
      };

      const onStart = async () => {
        setBusy('start');
        say('');
        try {
          const r = await jpost('/channel/start', {});
          if (r && r.channel) applyStatus({ channel: r.channel });
          if (r && r.ok) say(`已请求启动通道（端口 ${port}）`, 'good');
          else say((r && (r.message || r.hint || r.error)) || '启动失败', 'bad');
          setTimeout(loadDevices, 800);
        } catch (e) {
          say(String((e && e.message) || e), 'bad');
        } finally {
          setBusy('');
        }
      };

      const onStop = async () => {
        setBusy('stop');
        say('');
        try {
          const r = await jpost('/channel/stop', {});
          if (r && r.channel) applyStatus({ channel: r.channel });
          if (r && r.ok) say('已停止托管进程', 'good');
          else say((r && (r.hint || r.error)) || '停止失败（外部启动的通道面板停不掉）', 'bad');
        } catch (e) {
          say(String((e && e.message) || e), 'bad');
        } finally {
          setBusy('');
        }
      };

      const saveSession = async (value) => {
        setBusy('session');
        say('');
        try {
          const r = await jpost('/session', { sessionId: value });
          if (r && r.ok) {
            applyStatus({
              session: { sessionId: r.sessionId, bound: r.bound, mode: r.mode, hint: r.hint, source: r.source },
            });
            setSessionDraft(r.sessionId || '');
            if (r.bound) say(`已绑定投递会话：${r.sessionId}`, 'good');
            else say('已清除绑定：现在未绑定，手机消息不会投递', 'warn');
          } else {
            say((r && (r.message || r.error)) || '保存失败', 'bad');
          }
        } catch (e) {
          say(String((e && e.message) || e), 'bad');
        } finally {
          setBusy('');
        }
      };

      const onBindSession = () => saveSession(String(sessionDraft || '').trim());
      const onClearSession = () => saveSession('');

      const onPair = async () => {
        setBusy('pair');
        say('');
        try {
          const r = await jpost('/pair', {});
          const next = pairFromResponse(r);
          if (next) {
            // 乐观更新：响应一到就写进本地状态并开始倒计时，不等下一次 /status 轮询
            setPair(next);
            setPairExpired(false);
            say(`配对码已生成：${next.code}（${Math.round(next.ttlMs / 60000)} 分钟内有效，用一次即失效）`, 'good');
          } else {
            say((r && (r.hint || r.message || r.error)) || '生成配对码失败', 'bad');
          }
        } catch (e) {
          say(String((e && e.message) || e), 'bad');
        } finally {
          setBusy('');
        }
      };

      const onSaveVoice = async () => {
        setBusy('voice');
        say('');
        try {
          // 只提交用户真的动过的字段：st 还没读回来时不拿默认值覆盖已存的提供商
          const body = {};
          if (providerDraft !== null) body.provider = String(providerDraft);
          const key = String(keyDraft || '').trim();
          if (key) body.apiKey = key;
          const r = await jpost('/voice', body);
          if (r && r.ok) {
            if (r.voice) applyStatus({ voice: r.voice });
            setKeyDraft('');
            if (r.changed === false) {
              say('没有改动', '');
            } else {
              const mask = r.voice && r.voice.keyMask ? `（当前 key：${r.voice.keyMask}）` : '';
              say(`语音配置已保存${mask}${r.restartNeeded ? '；通道在跑，新 key 要重启通道才生效' : ''}`, 'good');
            }
          } else {
            say((r && (r.message || r.error)) || '保存失败', 'bad');
          }
        } catch (e) {
          say(String((e && e.message) || e), 'bad');
        } finally {
          setBusy('');
        }
      };

      const left = pairActive ? Math.max(0, Number(pair.expiresAt) - now) : 0;
      const leftText = pairActive
        ? `${Math.floor(left / 60000)}:${String(Math.floor((left % 60000) / 1000)).padStart(2, '0')}`
        : '已失效';

      const logLines = (st && st.log) || [];

      return h('div', { 'data-pb-page': '' },
        msg ? h('div', {
          'data-pb-msg': '',
          'data-good': msgTone === 'good' ? '' : undefined,
          'data-warn': msgTone === 'warn' ? '' : undefined,
          'data-bad': msgTone === 'bad' ? '' : undefined,
        }, msg) : null,

        fails >= 3
          ? h('div', { 'data-pb-msg': '', 'data-warn': '' },
            `读不到插件接口（已试 ${fails} 次）：常见原因是插件刚改完还没重启桌面端，或这个 profile 没有 webServer 服务。`)
          : null,

        h('div', { 'data-pb-row': '' },
          h(Badge, { tone: running ? 'good' : undefined }, running ? '通道在跑' : '通道停了'),
          h(Badge, {}, `端口 ${port}`),
          h(Badge, { tone: managed ? 'good' : 'warn' }, managed ? '面板托管' : (running ? '外部启动' : '未托管')),
          channel && channel.restarts ? h(Badge, {}, `自动拉起 ${channel.restarts}/${channel.maxRestarts}`) : null,
          st && st.pair && st.pair.active ? h(Badge, { tone: 'warn' }, '配对码有效中') : null,
          st && st.session && st.session.bound === false ? h(Badge, { tone: 'warn' }, '未绑定投递会话') : null,
          channel && channel.reachable && channel.health && channel.health.onlineDevice
            ? h(Badge, { tone: 'good' }, `手机在线 ${channel.health.onlineDevice}`)
            : null),

        /* ── 通道 ── */
        h(Section, { title: '通道' },
          h('div', { 'data-pb-row': '' },
            h('div', { 'data-pb-grow': '' },
              h('div', { 'data-pb-label': '' }, running ? '在跑' : '停了'),
              h('div', { 'data-pb-note': '' },
                channel && channel.pid ? `pid ${channel.pid}` : '没有托管进程')),
            h('button', {
              'data-pb-btn': '', 'data-primary': '', disabled: Boolean(busy) || Boolean(running),
              title: running ? '通道已经在跑；要重启先「停止」' : '拉起面板托管的 channel-server',
              onClick: onStart,
            }, busy === 'start' ? '启动中…' : '启动'),
            h('button', {
              'data-pb-btn': '', 'data-danger': '', disabled: Boolean(busy) || !managed,
              title: managed ? '停掉面板拉起的那个进程' : '外部启动的通道请用你自己的方式停',
              onClick: onStop,
            }, busy === 'stop' ? '停止中…' : '停止')),
          h('div', { 'data-pb-kv': '' },
            `入口：${(channel && channel.scriptPath) || '（未知）'}`),
          channel && channel.serverSource
            ? h('div', { 'data-pb-kv': '' },
              `来源：${
                channel.serverSource === 'config' ? 'config.channelServerDir'
                  : channel.serverSource === 'plugin-embedded' ? '插件自带的 channel-server'
                    : channel.serverSource === 'token-file' ? 'tokenFile 所在目录'
                      : '插件目录的兄弟 channel-server'
              }${channel.serverDir ? `（${channel.serverDir}）` : ''}`)
            : null,
          channel && channel.resolutionWarning
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' }, channel.resolutionWarning)
            : null,
          st && st.token && st.token.generated && st.token.persisted
            ? h('div', { 'data-pb-note': '' }, '首次运行：已自动生成 PHONE_BRIDGE_TOKEN 并写入 channel-server\\.env')
            : null,
          st && st.token && st.token.warning
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' }, st.token.warning)
            : null,
          channel && channel.lastExitCode !== null && channel.lastExitCode !== undefined
            ? h('div', { 'data-pb-kv': '' }, `最近退出：code=${channel.lastExitCode} · ${fmtTime(channel.lastExitAt)}`)
            : null,
          channel && channel.state === 'missing'
            ? h('div', { 'data-pb-msg': '', 'data-bad': '' }, channel.lastError || '找不到 channel-server 的 server.js')
            : null,
          channel && channel.state === 'crashed'
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' }, `通道进程退出了${channel.lastError ? '：' + channel.lastError : ''}`)
            : null,
          channel && channel.state === 'restarting'
            ? h('div', { 'data-pb-note': '' }, '进程挂了，正在按退避自动拉起…')
            : null,
          channel && channel.external
            ? h('div', { 'data-pb-note': '' }, '这个通道不是面板拉起的（外部进程）：面板能看状态、生成配对码，但「停止」停不掉它。')
            : null,
          h('div', { 'data-pb-note': '' },
            '托管进程随 DSH 启动（可在 cordis.patch.yml 里用 autoStart: false 关掉），DSH 退出时一起收掉；挂了会自动拉起，最多 5 次退避。')),

        /* ── 投递会话 ── */
        h(Section, { title: '投递会话' },
          h('div', { 'data-pb-row': '' },
            session && session.bound
              ? h('span', { 'data-pb-badge': '', 'data-good': '' }, `已绑定：${session.sessionId}`)
              : h('span', { 'data-pb-badge': '', 'data-warn': '' }, '未绑定：手机消息不会投递'),
            h('div', { 'data-pb-grow': '' }),
            h('button', {
              'data-pb-btn': '', 'data-primary': '', disabled: Boolean(busy), onClick: onBindSession,
            }, busy === 'session' ? '保存中…' : '绑定'),
            h('button', {
              'data-pb-btn': '', 'data-danger': '', disabled: Boolean(busy), onClick: onClearSession,
            }, '清除绑定')),
          h('input', {
            'data-pb-input': '', autoComplete: 'off', spellCheck: false,
            placeholder: 'session-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx 或裸 uuid；留空 = 未绑定',
            value: sessionDraft ?? '',
            onChange: (e) => setSessionDraft(e.target.value),
          }),
          h('div', { 'data-pb-note': '' },
            (session && session.hint) || '手机消息只投到这个会话；未绑定时会明确拒绝（409），不会去猜。'),
          session && session.file
            ? h('div', { 'data-pb-kv': '' },
              `设置文件：${session.file}${session.fallback ? '（默认目录建不出来，已降级）' : ''}`)
            : null,
          session && session.fallback
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' },
              session.fallbackReason || '默认目录建不出来，会话绑定降级到插件 data 目录。')
            : null,
          h('div', { 'data-pb-note': '' },
            '绑定写到 DSH 家目录下的 phone-bridge\\settings.json（优先于 cordis.patch.yml 的 sessionId），不写 profile。')),

        /* ── 配对 ── */
        h(Section, { title: '配对' },
          h('div', { 'data-pb-row': '' },
            h('button', {
              'data-pb-btn': '', 'data-primary': '', disabled: Boolean(busy), onClick: onPair,
            }, busy === 'pair' ? '生成中…' : (pair ? '重新生成配对码' : '生成配对码')),
            pairActive
              ? h('div', { 'data-pb-row': '' },
                h('span', { 'data-pb-code': '' }, pair.code || '······'),
                h('span', { 'data-pb-note': '' }, `还剩 ${leftText}`))
              : (pairExpired || (pair && pair.expiresAt <= now))
                ? h('div', { 'data-pb-note': '', 'data-warn': '' }, '配对码已过期，点「重新生成配对码」')
                : h('div', { 'data-pb-note': '' }, '还没有有效配对码')),
          h('div', { 'data-pb-note': '' },
            pairActive
              ? '手机 App 里输入这 6 位码完成配对；5 分钟内有效、用一次即失效。'
              : '点「生成配对码」让 channel-server 生成一个新的 6 位码（旧码会立刻作废）。'),
          pairActive && !pair.code
            ? h('div', { 'data-pb-note': '' }, '通道里已有一个配对码在有效期内（面板拿不到原文）；点「重新生成」会作废它并给新码。')
            : null,
          h('div', { 'data-pb-sec': '' },
            h('div', { 'data-pb-label': '' }, '电脑端地址（手机里填这个，点一下复制）'),
            addresses.length
              ? addresses.map((a) =>
                h('div', { 'data-pb-row': '', key: a.url },
                  h('button', {
                    'data-pb-btn': '', title: '点一下复制这个地址',
                    onClick: () => copyAddress(a.url),
                  }, a.url),
                  a.note ? h('span', { 'data-pb-note': '' }, a.note) : null))
              : h('div', { 'data-pb-note': '' }, '读取中…（拿不到非回环地址时会显示 127.0.0.1，仅本机）'),
            addresses.some((a) => a && a.loopback)
              ? h('div', { 'data-pb-msg': '', 'data-warn': '' },
                '没找到非回环 IPv4：这个地址只能本机用，手机连不上；检查 Tailscale 是否登录 / 网卡是否有地址。')
              : null),
          channel && channel.admin && channel.admin.ok === false && running
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' },
              `配对/设备接口不可用：${channel.admin.message || channel.admin.error}${channel.admin.hint ? '；' + channel.admin.hint : ''}`)
            : null),

        /* ── 设备 ── */
        h(Section, { title: '设备' },
          h('div', { 'data-pb-row': '' },
            h('button', { 'data-pb-btn': '', disabled: Boolean(busy), onClick: loadDevices }, busy === 'devices' ? '读取中…' : '刷新'),
            h('div', { 'data-pb-note': '' },
              devices === null ? '' : `共 ${devices.length} 台` + (devicesErr ? `（${devicesErr}）` : ''))),
          devices && devices.length
            ? h('div', { 'data-pb-sec': '' },
              devices.map((d) => h('div', { 'data-pb-dev': '', key: d.deviceId },
                h('div', { 'data-pb-dev-id': '' }, d.deviceId || '(无名)'),
                h('div', { 'data-pb-dev-meta': '' },
                  h('span', {}, `首次配对 ${fmtTime(d.pairedAt)}`),
                  h('span', {}, `最近活跃 ${d.lastActiveAt ? fmtTime(d.lastActiveAt) : '—'}`),
                  h('span', {}, d.online ? '在线' : '离线')))))
            : h('div', { 'data-pb-note': '' },
              devices === null ? '读取中…' : '还没有设备 —— 手机配对成功一次之后这里就有了。')),

        /* ── 语音 ── */
        h(Section, { title: '语音' },
          h('div', { 'data-pb-row': '' },
            h('div', { 'data-pb-grow': '' },
              h('div', { 'data-pb-label': '' }, '提供商'),
              h('div', { 'data-pb-note': '' }, 'DeepSeek 默认；选它时需要下面的 API key。⚠️ Edge / 本地目前是「未实现」的配置入口：只把选择写进 .env，channel-server 的 TTS 仍只支持 DeepSeek，不改变实际播报。')),
            h('select', {
              'data-pb-input': '', style: { width: '190px' },
              value: providerDraft || DEFAULT_PROVIDER,
              onChange: (e) => {
                setProviderEdited(true);
                setProviderDraft(e.target.value);
              },
            }, PROVIDERS.map(([value, label]) => h('option', { key: value, value }, label)))),
          h('div', { 'data-pb-row': '' },
            h('div', { 'data-pb-grow': '' },
              h('div', { 'data-pb-label': '' }, 'DeepSeek API key'),
              h('div', { 'data-pb-note': '' },
                voice && voice.keyMask
                  ? `已设置：${voice.keyMask}（输入新值会覆盖）`
                  : '未设置（只存进 channel-server\\.env，面板只回掩码）')),
            h('button', {
              'data-pb-btn': '', disabled: Boolean(busy), onClick: onSaveVoice,
            }, busy === 'voice' ? '保存中…' : '保存')),
          h('input', {
            'data-pb-input': '', type: 'password', autoComplete: 'off', spellCheck: false,
            placeholder: voice && voice.keyMask ? `当前 ${voice.keyMask} —— 粘贴新值覆盖` : '粘贴 API key（sk-…）',
            value: keyDraft,
            onChange: (e) => setKeyDraft(e.target.value),
          }),
          voice && voice.envPrecedence
            ? h('div', { 'data-pb-msg': '', 'data-warn': '' },
              '环境变量里已经有 DEEPSEEK_API_KEY，它的优先级高于 .env：这里保存的值不会生效，要改请改环境变量。')
            : voice && voice.keySource === 'none'
              ? h('div', { 'data-pb-note': '' }, 'channel-server 现在没有 key：语音识别与 DeepSeek TTS 都会回不可用（Edge / 本地选项是配置入口，语音链路暂未接）。')
              : null,
          h('div', { 'data-pb-note': '' },
            'key 只写进 channel-server\\.env 的 DEEPSEEK_API_KEY；面板回显永远是掩码。通道正在跑时改 key 要重启通道才生效。')),

        /* ── 日志 ── */
        h(Section, { title: '日志（channel-server 最后十几行）' },
          h('pre', { 'data-pb-pre': '' }, logLines.length ? logLines.join('\n') : '还没有日志 — 通道没启动过时这里就是空的。')),
      );
    }

    return {
      inject: ['slots'],
      // 给 selfcheck 用的纯函数（浏览器里用不到；放这里免得把逻辑散成两份）
      __test: { DEFAULT_PROVIDER, PROVIDERS, mergePair, pairFromResponse },
      apply(ctx) {
        const style = document.createElement('style');
        style.setAttribute('data-plugin-css', 'phone-bridge');
        style.textContent = CSS;
        document.head.appendChild(style);
        ctx.effect(() => () => style.remove(), 'phone-bridge: 设置页样式');

        const slots = (typeof ctx.get === 'function' ? ctx.get('slots') : undefined) || ctx.slots;
        if (!slots) return;
        try {
          slots.inject('settings.section', () =>
            slots.register(
              { name: 'settings.section', id: 'phone-bridge', order: 40, label: '语音通道' },
              () => h(Panel),
            ),
          );
        } catch (e) {
          /* 槽位没就绪：设置页不出现，别把页面弄崩 */
        }
      },
    };
  },
});
