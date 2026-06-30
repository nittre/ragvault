import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { login, changePassword } from '../api/auth'
import { useAuthStore } from '../stores/authStore'

export default function LoginPage() {
  const navigate = useNavigate()
  const { setAuth } = useAuthStore()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(false)

  // 비밀번호 변경 모달 상태
  const [showChangeModal, setShowChangeModal] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [changeError, setChangeError] = useState<string | null>(null)
  const [isChanging, setIsChanging] = useState(false)

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setIsLoading(true)
    try {
      const res = await login({ email, password })
      if (res.passwordChangeRequired) {
        setShowChangeModal(true)
        return
      }
      setAuth(res.email, res.role)
      navigate('/')
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '로그인에 실패했습니다.'
      setError(msg)
    } finally {
      setIsLoading(false)
    }
  }

  const handleChangePassword = async (e: React.FormEvent) => {
    e.preventDefault()
    if (newPassword !== confirmPassword) {
      setChangeError('비밀번호가 일치하지 않습니다.')
      return
    }
    setChangeError(null)
    setIsChanging(true)
    try {
      await changePassword({ email, currentPassword: password, newPassword })
      const res = await login({ email, password: newPassword })
      setAuth(res.email, res.role)
      navigate('/')
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : '비밀번호 변경에 실패했습니다.'
      setChangeError(msg)
    } finally {
      setIsChanging(false)
    }
  }

  return (
    <div className="min-h-screen bg-gray-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-md w-full max-w-md p-8">
        <h1 className="text-2xl font-bold text-gray-900 mb-2 text-center">RagVault</h1>
        <p className="text-sm text-gray-500 text-center mb-8">사내 RAG 질의응답 시스템</p>

        <form onSubmit={handleLogin} className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">이메일</label>
            <input
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              required
              autoFocus
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
              placeholder="example@company.com"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">비밀번호</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
              placeholder="••••••••"
            />
          </div>

          {error && (
            <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={isLoading}
            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white rounded-lg px-4 py-2 font-medium text-sm transition-colors"
          >
            {isLoading ? '로그인 중...' : '로그인'}
          </button>
        </form>
      </div>

      {/* 비밀번호 변경 모달 */}
      {showChangeModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-8">
            <h2 className="text-lg font-bold text-gray-900 mb-2">비밀번호 변경 필요</h2>
            <p className="text-sm text-gray-500 mb-6">초기 비밀번호를 변경해야 합니다.</p>

            <form onSubmit={handleChangePassword} className="space-y-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">새 비밀번호</label>
                <input
                  type="password"
                  value={newPassword}
                  onChange={e => setNewPassword(e.target.value)}
                  required
                  autoFocus
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">새 비밀번호 확인</label>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={e => setConfirmPassword(e.target.value)}
                  required
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm"
                />
              </div>

              {changeError && (
                <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                  {changeError}
                </p>
              )}

              <button
                type="submit"
                disabled={isChanging}
                className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white rounded-lg px-4 py-2 font-medium text-sm transition-colors"
              >
                {isChanging ? '변경 중...' : '비밀번호 변경'}
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
