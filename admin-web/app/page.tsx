'use client';

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';

const API = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';
type CaseStatus = 'AWAITING_REVIEW' | 'RESOLVED';
type FinalAction = 'NONE' | 'HIDE' | 'DELETE' | 'BAN';
type Tab = 'queue' | 'resolved' | 'appeals';
type ModerationCase = { id:string; targetType:string; targetId:string; status:CaseStatus; reportCount:number; engine?:string; recommendedDecision?:string; confidence?:number; rationale?:string; ruleCodes?:string[]; finalAction?:FinalAction; assignedTo?:string; reviewDueAt?:string; createdAt:string; overdue?:boolean };
type CaseDetail = { moderationCase:ModerationCase; content?:{ title?:string; body?:string; authorId:string; mediaUrl?:string }; auditTrail:Array<{ action:string; actorId?:string; payload?:Record<string,unknown>; at:string }> };
type Appeal = { id:string; caseId:string; appellantId:string; reason:string; status:string; response?:string; createdAt:string };
type Session = { accessToken:string; userId:string; username:string };
type EngineStatus = { activeEngine:string; fallbackEngine:string; llmActive:boolean };
type EvidenceStrength = 'SETTLED' | 'LEANING' | 'OPEN';
type Brief = { outcome:'COMPLETE'|'PARTIAL'|'INCONCLUSIVE'; summary:string; recommendation?:FinalAction; evidenceStrength?:EvidenceStrength; counterEvidence?:string; citedCaseIds:string[]; promptVersion:string; producedAt:string };

const ACTION_LABEL:Record<FinalAction,string> = { NONE:'不处理', HIDE:'隐藏', DELETE:'删除', BAN:'封禁作者' };
// Deliberately not percentages. The band replaced a confidence number precisely
// because nothing calibrates it, and rendering it as "85%" would put the false
// precision straight back.
const EVIDENCE_LABEL:Record<EvidenceStrength,string> = { SETTLED:'证据明确', LEANING:'有倾向', OPEN:'两可' };

function elapsed(value:string) {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} 小时` : `${Math.floor(hours / 24)} 天`;
}
function errorText(error:unknown) { return error instanceof Error ? error.message : '请求失败，请稍后重试。'; }

export default function Home() {
  // Restored after the first paint, not during it. Reading sessionStorage in the
  // initial state made the server render a logged-out page and the browser render
  // a logged-in one from the same code, which is a hydration mismatch: React warns
  // in development and, in production, silently keeps whichever tree it built
  // first. The cost is one frame showing the login form to somebody already
  // signed in, which `restoring` covers.
  const [session, setSession] = useState<Session|null>(null);
  const [restoring, setRestoring] = useState(true);

  useEffect(() => {
    try {
      const saved = sessionStorage.getItem('campusguard.admin.session');
      if (saved) setSession(JSON.parse(saved));
    } catch { sessionStorage.removeItem('campusguard.admin.session'); }
    finally { setRestoring(false); }
  }, []);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [tab, setTab] = useState<Tab>('queue');
  const [cases, setCases] = useState<ModerationCase[]>([]);
  const [appeals, setAppeals] = useState<Appeal[]>([]);
  const [detail, setDetail] = useState<CaseDetail|null>(null);
  const [engine, setEngine] = useState<EngineStatus|null>(null);
  const [note, setNote] = useState('');
  const [brief, setBrief] = useState<Brief|null>(null);
  const [briefBusy, setBriefBusy] = useState(false);
  const [briefOff, setBriefOff] = useState('');
  const [appealResponses, setAppealResponses] = useState<Record<string,string>>({});
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');

  const logout = useCallback(() => {
    sessionStorage.removeItem('campusguard.admin.session');
    setSession(null); setDetail(null); setBrief(null); setCases([]); setAppeals([]);
  }, []);

  const request = useCallback(async <T,>(path:string, init?:RequestInit):Promise<T> => {
    const response = await fetch(`${API}${path}`, {
      ...init,
      headers: {
        Accept:'application/json',
        ...(init?.body ? {'Content-Type':'application/json'} : {}),
        ...(session ? {Authorization:`Bearer ${session.accessToken}`} : {}),
        ...init?.headers,
      },
    });
    if (response.status === 401 && session) logout();
    if (!response.ok) {
      const problem = await response.json().catch(() => ({})) as {detail?:string;title?:string};
      throw new Error(problem.detail || problem.title || `HTTP ${response.status}`);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }, [logout, session]);

  const refresh = useCallback(async () => {
    if (!session) return;
    setBusy(true); setMessage('');
    try {
      if (tab === 'appeals') setAppeals(await request<Appeal[]>('/api/admin/appeals?status=PENDING&size=100'));
      else {
        const status:CaseStatus = tab === 'queue' ? 'AWAITING_REVIEW' : 'RESOLVED';
        const rows = await request<ModerationCase[]>(`/api/admin/moderation-cases?status=${status}&size=100`);
        const checkedAt = Date.now();
        setCases(rows.map(item => ({...item, overdue:!!item.reviewDueAt && new Date(item.reviewDueAt).getTime() < checkedAt})));
      }
      setEngine(await request<EngineStatus>('/api/moderation/status'));
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  }, [request, session, tab]);
  useEffect(() => {
    const timer = window.setTimeout(() => void refresh(), 0);
    return () => window.clearTimeout(timer);
  }, [refresh]);

  const login = async (event:FormEvent) => {
    event.preventDefault(); setBusy(true); setMessage('');
    try {
      const response = await fetch(`${API}/api/auth/login`, {method:'POST', headers:{'Content-Type':'application/json',Accept:'application/json'}, body:JSON.stringify({username,password})});
      const body = await response.json().catch(() => ({})) as {detail?:string;accessToken?:string;userId?:string;username?:string};
      if (!response.ok) throw new Error(body.detail || '用户名或密码不正确。');
      if (!body.accessToken || !body.userId || !body.username) throw new Error('登录响应不完整，请检查后端版本。');
      const next = {accessToken:body.accessToken,userId:body.userId,username:body.username};
      sessionStorage.setItem('campusguard.admin.session', JSON.stringify(next));
      setSession(next); setPassword('');
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  };

  const openCase = async (id:string) => {
    setBusy(true); setMessage(''); setBrief(null);
    try {
      setDetail(await request<CaseDetail>(`/api/admin/moderation-cases/${id}`)); setNote('');
      // Fetched, not run. Opening a case shows a brief somebody already paid for
      // and never starts one on its own.
      setBrief(await request<Brief|undefined>(`/api/admin/moderation-cases/${id}/investigation`) ?? null);
    }
    catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  };

  const investigate = async (force:boolean) => {
    if (!detail) return;
    setBriefBusy(true); setMessage('');
    try {
      setBrief(await request<Brief>(`/api/admin/moderation-cases/${detail.moderationCase.id}/investigate?force=${force}`, {method:'POST'}));
    } catch (error) {
      // A switched-off assistant is a configuration, not a fault. It greys the
      // button out with a reason rather than showing an error the reviewer
      // would reasonably try to act on.
      const text = errorText(error);
      if (text.includes('not enabled')) setBriefOff(text); else setMessage(text);
    }
    finally { setBriefBusy(false); }
  };

  const mutateCase = async (path:string, init:RequestInit) => {
    setBusy(true); setMessage('');
    try { setDetail(await request<CaseDetail>(path, init)); await refresh(); }
    catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  };
  const decide = (action:FinalAction) => detail && mutateCase(`/api/admin/moderation-cases/${detail.moderationCase.id}/decision`, {method:'POST',body:JSON.stringify({action,note})});

  const decideAppeal = async (appeal:Appeal, decision:'UPHOLD'|'OVERTURN') => {
    setBusy(true); setMessage('');
    try {
      await request(`/api/admin/appeals/${appeal.id}/decision`, {method:'POST',body:JSON.stringify({decision,response:appealResponses[appeal.id]||''})});
      setAppealResponses(values => { const next={...values}; delete next[appeal.id]; return next; }); await refresh();
    } catch (error) { setMessage(errorText(error)); }
    finally { setBusy(false); }
  };

  const metrics = useMemo(() => ({
    overdue:cases.filter(item => item.overdue).length,
    assigned:cases.filter(item => item.assignedTo).length,
  }), [cases]);

  if (restoring) return <main className="login-shell"><div className="login-card"><div className="brand-mark large">D</div><p className="eyebrow">CampusGuard</p></div></main>;
  if (!session) return <main className="login-shell"><form className="login-card" onSubmit={login}>
    <div className="brand-mark large">D</div><p className="eyebrow">CampusGuard / 安全入口</p><h1>管理员登录</h1>
    <p className="login-copy">使用后端创建的管理员账号进入审核工作台。令牌仅保存在当前浏览器会话。</p>
    <label>用户名<input autoComplete="username" value={username} onChange={e=>setUsername(e.target.value)} required /></label>
    <label>密码<input type="password" autoComplete="current-password" value={password} onChange={e=>setPassword(e.target.value)} required /></label>
    {message && <p className="alert">{message}</p>}
    <button className="primary login-button" disabled={busy}>{busy?'正在验证…':'登录工作台'}</button><Link className="text-link" href="/reset-password">忘记密码？</Link><small>API：{API}</small>
  </form></main>;

  return <main className="app-shell">
    <aside className="sidebar"><div className="brand-mark">D</div><nav aria-label="主导航">
      <button className={`nav-item ${tab==='queue'?'active':''}`} onClick={()=>{setTab('queue');setDetail(null);}}>队</button>
      <button className={`nav-item ${tab==='resolved'?'active':''}`} onClick={()=>{setTab('resolved');setDetail(null);}}>录</button>
      <button className={`nav-item ${tab==='appeals'?'active':''}`} onClick={()=>{setTab('appeals');setDetail(null);}}>诉</button>
    </nav><button className="profile-dot" title="退出登录" onClick={logout}>{session.username.slice(0,1).toUpperCase()}</button></aside>
    <section className="workspace">
      <header className="topbar"><div><p className="eyebrow">CampusGuard / De-Moderation</p><h1>{tab==='queue'?'审核工作台':tab==='resolved'?'裁决记录':'申诉中心'}</h1></div>
        <div className={`engine-pill ${engine?.llmActive?'':'fallback'}`}><span />{engine?.llmActive?`${engine.activeEngine} 正常运行`:`当前使用 ${engine?.activeEngine||'规则引擎'}`}</div></header>
      {message && <p className="alert page-alert">{message}</p>}
      <div className="metrics">
        <article><strong>{String(tab==='appeals'?appeals.length:cases.length).padStart(2,'0')}</strong><span>{tab==='appeals'?'待处理申诉':'当前列表'}</span><small>来自服务器实时数据</small></article>
        <article><strong>{String(metrics.overdue).padStart(2,'0')}</strong><span>已超过 SLA</span><small>按 reviewDueAt 计算</small></article>
        <article><strong>{String(metrics.assigned).padStart(2,'0')}</strong><span>已被认领</span><small>避免多人重复裁决</small></article>
        <article><strong>{engine?.llmActive?'AI':'规则'}</strong><span>当前审核引擎</span><small>{engine?.activeEngine||'正在读取状态'}</small></article>
      </div>
      <div className={`content-grid ${detail?'with-detail':''}`}>
        <section className="queue-panel"><div className="section-heading"><div><p className="eyebrow">实时数据</p><h2>{tab==='appeals'?'等待裁定的申诉':'需要判断的案件'}</h2></div><button className="filter-button" onClick={refresh} disabled={busy}>{busy?'刷新中…':'刷新'}</button></div>
          {tab==='appeals'?<div className="case-list">{appeals.map(appeal=><article className="case-card" key={appeal.id}>
            <div className="case-head"><span className="risk">申诉</span><span>{elapsed(appeal.createdAt)}前</span></div><p className="case-copy">{appeal.reason}</p>
            <div className="case-meta"><span>案件 {appeal.caseId.slice(0,8)}</span><span>用户 {appeal.appellantId.slice(0,8)}</span></div>
            <textarea placeholder="给申诉人的处理说明（可选）" value={appealResponses[appeal.id]||''} onChange={e=>setAppealResponses(values=>({...values,[appeal.id]:e.target.value}))} />
            <div className="actions"><button className="secondary" onClick={()=>decideAppeal(appeal,'UPHOLD')} disabled={busy}>维持原判</button><button className="primary" onClick={()=>decideAppeal(appeal,'OVERTURN')} disabled={busy}>撤销原判</button></div>
          </article>)}{!appeals.length&&!busy&&<div className="empty">目前没有待处理申诉。</div>}</div>
          :<div className="case-list">{cases.map(item=>{const overdue=item.overdue;return <article className={`case-card ${detail?.moderationCase.id===item.id?'selected':''}`} key={item.id}>
            <div className="case-head"><span className={`risk ${overdue?'danger':''}`}>{overdue?'已超 SLA':item.targetType==='POST'?'帖子':'评论'}</span><span>等待 {elapsed(item.createdAt)}</span></div>
            <p className="case-copy">{item.rationale||'模型尚未给出说明，请打开案件查看上下文。'}</p>
            <div className="case-meta"><span><b>{item.reportCount}</b> 次举报</span><span>{item.recommendedDecision||'待分析'} · {item.confidence==null?'—':`${Math.round(item.confidence*100)}%`}</span><span>{item.engine||'等待 worker'}</span></div>
            <div className="actions"><button className="primary" onClick={()=>openCase(item.id)}>查看并处理 →</button></div>
          </article>;})}{!cases.length&&!busy&&<div className="empty">这个队列目前是空的。</div>}</div>}
        </section>
        {detail?<aside className="detail-panel"><button className="detail-close" onClick={()=>setDetail(null)}>×</button><p className="eyebrow">案件 {detail.moderationCase.id.slice(0,8)}</p>
          <h2>{detail.content?.title||(detail.moderationCase.targetType==='POST'?'举报帖子':'举报评论')}</h2><p className="content-body">{detail.content?.body||'原内容已不可用。'}</p>
          {detail.content?.mediaUrl&&<img className="evidence" src={`${API}${detail.content.mediaUrl}`} alt="举报内容附件" /> /* eslint-disable-line @next/next/no-img-element */}
          <dl><div><dt>建议</dt><dd>{detail.moderationCase.recommendedDecision||'—'}</dd></div><div><dt>置信度</dt><dd>{detail.moderationCase.confidence==null?'—':`${Math.round(detail.moderationCase.confidence*100)}%`}</dd></div><div><dt>规则</dt><dd>{detail.moderationCase.ruleCodes?.join(', ')||'—'}</dd></div></dl>
          <section className="brief">
            <div className="brief-head"><p className="eyebrow">调查助手</p>
              <button className="secondary" disabled={briefBusy||!!briefOff} onClick={()=>investigate(!!brief)}>{briefBusy?'调查中…':brief?'重新调查':'调查'}</button></div>
            {briefOff?<p className="brief-empty">{briefOff}</p>
              :brief?<>
                {brief.outcome==='COMPLETE'&&brief.recommendation
                  ?<p className="brief-verdict">建议 <b>{ACTION_LABEL[brief.recommendation]}</b>{brief.evidenceStrength&&<span className={`band ${brief.evidenceStrength.toLowerCase()}`}>{EVIDENCE_LABEL[brief.evidenceStrength]}</span>}</p>
                  :<p className="brief-verdict incomplete">{brief.outcome==='PARTIAL'?'调查未完成':'助手没有得出结论'}</p>}
                <p className="brief-summary">{brief.summary}</p>
                {brief.counterEvidence&&<p className="brief-against"><b>反过来说</b>{brief.counterEvidence}</p>}
                <p className="brief-meta">{brief.promptVersion} · 引用 {brief.citedCaseIds.length} 个案件 · {new Date(brief.producedAt).toLocaleString('zh-CN')}</p>
              </>
              :<p className="brief-empty">尚未调查。助手会查作者过往处理记录与同规则先例，只读，不改变案件，最终处置仍由你决定。</p>}
          </section>
          {detail.moderationCase.status==='AWAITING_REVIEW'&&<div className="assignment-row"><span>{detail.moderationCase.assignedTo?`已由 ${detail.moderationCase.assignedTo.slice(0,8)} 认领`:'尚未认领'}</span>
            {detail.moderationCase.assignedTo?<button className="secondary" onClick={()=>mutateCase(`/api/admin/moderation-cases/${detail.moderationCase.id}/assignment`,{method:'DELETE'})}>释放</button>:<button className="secondary" onClick={()=>mutateCase(`/api/admin/moderation-cases/${detail.moderationCase.id}/assignment`,{method:'POST'})}>认领</button>}</div>}
          {detail.moderationCase.status==='RESOLVED'&&<p className="resolved-banner">最终处理：{detail.moderationCase.finalAction}</p>}
          <textarea placeholder={detail.moderationCase.status==='RESOLVED'?'说明为什么修订原裁决':'记录裁决原因，便于审计和申诉复核'} value={note} onChange={e=>setNote(e.target.value)} />
          <div className="decision-grid"><button onClick={()=>decide('NONE')} disabled={busy||detail.moderationCase.finalAction==='NONE'}>不处理</button><button onClick={()=>decide('HIDE')} disabled={busy||detail.moderationCase.finalAction==='HIDE'}>隐藏</button><button onClick={()=>decide('DELETE')} disabled={busy||detail.moderationCase.finalAction==='DELETE'}>删除</button><button className="danger-button" onClick={()=>decide('BAN')} disabled={busy||detail.moderationCase.finalAction==='BAN'}>封禁作者</button></div>
          <details><summary>审计记录（{detail.auditTrail.length}）</summary>{detail.auditTrail.map((entry,index)=><p className="audit" key={`${entry.at}-${index}`}><b>{entry.action}</b><br/><span>{new Date(entry.at).toLocaleString('zh-CN')}</span></p>)}</details>
        </aside>:<aside className="status-panel"><p className="eyebrow">服务健康</p><h2>{engine?'审核链路已连接':'正在检查服务'}</h2>
          <div className="health-row"><span>API 地址</span><b>{API.replace(/^https?:\/\//,'')}</b></div><div className="health-row"><span>当前引擎</span><b>{engine?.activeEngine||'—'}</b></div><div className="health-row"><span>降级引擎</span><b>{engine?.fallbackEngine||'—'}</b></div><div className="health-row"><span>登录管理员</span><b>{session.username}</b></div>
          <p className="status-help">选择一个案件后，这里会显示完整内容、附件、模型依据、认领状态和审计记录。</p></aside>}
      </div>
    </section>
  </main>;
}
