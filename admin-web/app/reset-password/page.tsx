'use client';

import Link from 'next/link';
import { FormEvent, useState, useSyncExternalStore } from 'react';

const API = process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080';
const subscribeToLocation = () => () => {};
const resetToken = () => new URLSearchParams(window.location.search).get('token') || '';

export default function ResetPassword() {
  const token = useSyncExternalStore(subscribeToLocation, resetToken, () => '');
  const [account, setAccount] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true); setError(''); setMessage('');
    try {
      if (token) {
        if (password !== confirmation) throw new Error('两次输入的密码不一致。');
        const response = await fetch(`${API}/api/auth/password-reset/confirm`, {
          method: 'POST', headers: {'Content-Type':'application/json'},
          body: JSON.stringify({token,newPassword:password}),
        });
        if (!response.ok) {
          const problem = await response.json().catch(() => ({})) as {detail?:string};
          throw new Error(problem.detail || '重置链接无效或已过期。');
        }
        setMessage('密码已更新。现在可以返回登录。'); setPassword(''); setConfirmation('');
      } else {
        const response = await fetch(`${API}/api/auth/password-reset/request`, {
          method: 'POST', headers: {'Content-Type':'application/json'},
          body: JSON.stringify({account}),
        });
        if (!response.ok) {
          const problem = await response.json().catch(() => ({})) as {detail?:string};
          throw new Error(problem.detail || '暂时无法发送重置邮件。');
        }
        setMessage('如果该账号配置了邮箱，重置邮件已经发送。');
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '请求失败，请稍后重试。');
    } finally {
      setBusy(false);
    }
  };

  return <main className="login-shell"><form className="login-card" onSubmit={submit}>
    <div className="brand-mark large">D</div><p className="eyebrow">CampusGuard / 账号恢复</p>
    <h1>{token?'设置新密码':'找回密码'}</h1>
    {token?<>
      <label>新密码<input type="password" minLength={8} maxLength={128} autoComplete="new-password" value={password} onChange={event=>setPassword(event.target.value)} required /></label>
      <label>确认新密码<input type="password" minLength={8} maxLength={128} autoComplete="new-password" value={confirmation} onChange={event=>setConfirmation(event.target.value)} required /></label>
    </>:<label>用户名或邮箱<input autoComplete="username" value={account} onChange={event=>setAccount(event.target.value)} required /></label>}
    {error&&<p className="alert">{error}</p>}{message&&<p className="success-message">{message}</p>}
    <button className="primary login-button" disabled={busy}>{busy?'正在提交…':token?'更新密码':'发送重置邮件'}</button>
    <Link className="text-link" href="/">返回登录</Link>
  </form></main>;
}
