import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { UserPlus } from 'lucide-react'
import { getUsers, createUser, updateUser, deleteUser, resetPassword } from '../../api/admin/users'
import { useAuthStore } from '../../stores/authStore'
import type { AdminUser } from '../../types'

const ROLES = ['ADMIN', 'SUPER_ADMIN']

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${
        active ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
      }`}
    >
      {active ? '활성' : '비활성'}
    </span>
  )
}

export default function UsersPage() {
  const qc = useQueryClient()
  const myRole = useAuthStore(s => s.role)
  const isSuperAdmin = myRole === 'SUPER_ADMIN'

  const { data: users = [], isLoading, error } = useQuery({
    queryKey: ['admin-users'],
    queryFn: getUsers,
  })

  const createMut = useMutation({
    mutationFn: createUser,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-users'] })
      setShowForm(false)
      setForm({ email: '', name: '', role: 'ADMIN', password: '', confirmPassword: '' })
      setCreateError(null)
    },
  })

  const updateMut = useMutation({
    mutationFn: ({ email, body }: { email: string; body: Parameters<typeof updateUser>[1] }) =>
      updateUser(email, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
  })

  const deleteMut = useMutation({
    mutationFn: deleteUser,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-users'] }),
  })

  const resetMut = useMutation({
    mutationFn: ({ email, newPassword }: { email: string; newPassword: string }) =>
      resetPassword(email, newPassword),
    onSuccess: () => {
      setResetTarget(null)
      setResetForm({ newPassword: '', confirmPassword: '' })
      setResetError(null)
    },
  })

  const [showForm, setShowForm] = useState(false)
  const [form, setForm] = useState({ email: '', name: '', role: 'ADMIN', password: '', confirmPassword: '' })
  const [createError, setCreateError] = useState<string | null>(null)

  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null)
  const [resetForm, setResetForm] = useState({ newPassword: '', confirmPassword: '' })
  const [resetError, setResetError] = useState<string | null>(null)

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    if (form.password !== form.confirmPassword) {
      setCreateError('비밀번호가 일치하지 않습니다.')
      return
    }
    setCreateError(null)
    createMut.mutate({ email: form.email, name: form.name, role: form.role, password: form.password })
  }

  const handleDelete = (user: AdminUser) => {
    if (window.confirm(`${user.email} 을(를) 삭제하시겠습니까?`)) {
      deleteMut.mutate(user.email)
    }
  }

  const handleResetPassword = (e: React.FormEvent) => {
    e.preventDefault()
    if (!resetTarget) return
    if (resetForm.newPassword !== resetForm.confirmPassword) {
      setResetError('비밀번호가 일치하지 않습니다.')
      return
    }
    setResetError(null)
    resetMut.mutate({ email: resetTarget.email, newPassword: resetForm.newPassword })
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-semibold text-gray-900">사용자 관리</h1>
        {isSuperAdmin && (
          <button
            onClick={() => setShowForm(v => !v)}
            className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium transition-colors"
          >
            <UserPlus size={15} /> 사용자 추가
          </button>
        )}
      </div>

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">이메일</label>
            <input
              required
              type="email"
              value={form.email}
              onChange={e => setForm(p => ({ ...p, email: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-52 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">이름</label>
            <input
              required
              value={form.name}
              onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-36 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">초기 비밀번호</label>
            <input
              required
              type="password"
              autoComplete="new-password"
              minLength={8}
              value={form.password}
              onChange={e => setForm(p => ({ ...p, password: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-40 focus:outline-none focus:ring-2 focus:ring-blue-500"
              placeholder="8자 이상"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">비밀번호 확인</label>
            <input
              required
              type="password"
              autoComplete="new-password"
              minLength={8}
              value={form.confirmPassword}
              onChange={e => setForm(p => ({ ...p, confirmPassword: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-40 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">역할</label>
            <select
              value={form.role}
              onChange={e => setForm(p => ({ ...p, role: e.target.value }))}
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {ROLES.map(r => <option key={r}>{r}</option>)}
            </select>
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium transition-colors"
          >
            {createMut.isPending ? '저장 중…' : '저장'}
          </button>
          <button
            type="button"
            onClick={() => setShowForm(false)}
            className="text-gray-500 hover:text-gray-700 px-3 py-1.5 text-sm transition-colors"
          >
            취소
          </button>
          {createError && (
            <p className="w-full text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
              {createError}
            </p>
          )}
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {!isLoading && (
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['이메일', '이름', '역할', '상태', '생성일', ''].map(h => (
                  <th
                    key={h}
                    className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide"
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {users.map(u => (
                <tr key={u.email} className="hover:bg-gray-50">
                  <td className="px-4 py-3 text-gray-900">{u.email}</td>
                  <td className="px-4 py-3 text-gray-700">{u.name}</td>
                  <td className="px-4 py-3">
                    {isSuperAdmin ? (
                      <select
                        value={u.role}
                        onChange={e =>
                          updateMut.mutate({ email: u.email, body: { role: e.target.value } })
                        }
                        className="border border-gray-300 rounded px-2 py-1 text-xs focus:outline-none focus:ring-2 focus:ring-blue-500"
                      >
                        {ROLES.map(r => <option key={r}>{r}</option>)}
                      </select>
                    ) : (
                      <span className="text-gray-700">{u.role}</span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => updateMut.mutate({ email: u.email, body: { active: !u.active } })}
                    >
                      <StatusBadge active={u.active} />
                    </button>
                  </td>
                  <td className="px-4 py-3 text-gray-400 text-xs">
                    {new Date(u.createdAt).toLocaleDateString()}
                  </td>
                  <td className="px-4 py-3">
                    {isSuperAdmin && (
                      <div className="flex items-center gap-3">
                        <button
                          onClick={() => {
                            setResetTarget(u)
                            setResetForm({ newPassword: '', confirmPassword: '' })
                            setResetError(null)
                          }}
                          className="text-blue-500 hover:text-blue-700 text-xs transition-colors"
                        >
                          비밀번호 재설정
                        </button>
                        <button
                          onClick={() => handleDelete(u)}
                          className="text-red-400 hover:text-red-600 text-xs transition-colors"
                        >
                          삭제
                        </button>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
              {users.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-4 py-8 text-center text-gray-400 text-sm">
                    사용자가 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {resetTarget && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-6">
            <h2 className="text-lg font-bold text-gray-900 mb-1">비밀번호 재설정</h2>
            <p className="text-sm text-gray-500 mb-6">{resetTarget.email}</p>

            <form onSubmit={handleResetPassword} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">새 비밀번호</label>
                <input
                  required
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  autoFocus
                  value={resetForm.newPassword}
                  onChange={e => setResetForm(p => ({ ...p, newPassword: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                  placeholder="8자 이상"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">새 비밀번호 확인</label>
                <input
                  required
                  type="password"
                  autoComplete="new-password"
                  minLength={8}
                  value={resetForm.confirmPassword}
                  onChange={e => setResetForm(p => ({ ...p, confirmPassword: e.target.value }))}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>

              {resetError && (
                <p className="text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                  {resetError}
                </p>
              )}

              <div className="flex gap-2">
                <button
                  type="submit"
                  disabled={resetMut.isPending}
                  className="flex-1 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-lg px-4 py-2 text-sm font-medium transition-colors"
                >
                  {resetMut.isPending ? '재설정 중…' : '재설정'}
                </button>
                <button
                  type="button"
                  onClick={() => setResetTarget(null)}
                  className="text-gray-500 hover:text-gray-700 px-4 py-2 text-sm transition-colors"
                >
                  취소
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
