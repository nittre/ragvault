import React, { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus, Settings, MoreHorizontal, Pin, PinOff, Pencil, Trash2, Loader2 } from 'lucide-react'
import { useChatStore } from '../../stores/chatStore'
import { useAuthStore } from '../../stores/authStore'

interface ConversationListProps {
  className?: string
}

export default function ConversationList({ className = '' }: ConversationListProps) {
  const {
    conversations, activeConvId, pendingConvIds,
    createConversation, setActiveConv, deleteConversation, updateConvTitle, togglePin,
  } = useChatStore()
  const role = useAuthStore(s => s.role)
  const isAdmin = role === 'ADMIN' || role === 'SUPER_ADMIN'

  const [menuOpenId, setMenuOpenId] = useState<string | null>(null)
  const [renamingId, setRenamingId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')

  const menuContainerRef = useRef<HTMLDivElement>(null)
  const renameInputRef = useRef<HTMLInputElement>(null)

  const sorted = [...conversations].sort((a, b) => {
    if (a.pinned && !b.pinned) return -1
    if (!a.pinned && b.pinned) return 1
    return b.updatedAt - a.updatedAt
  })

  useEffect(() => {
    if (!activeConvId && sorted.length > 0) {
      setActiveConv(sorted[0].id)
    }
  }, [sorted.length]) // eslint-disable-line react-hooks/exhaustive-deps

  // 메뉴 외부 클릭 닫기
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuContainerRef.current && !menuContainerRef.current.contains(e.target as Node)) {
        setMenuOpenId(null)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const handleNew = () => {
    const id = createConversation()
    setActiveConv(id)
  }

  const openMenu = (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    setMenuOpenId(prev => (prev === id ? null : id))
  }

  const startRename = (e: React.MouseEvent, id: string, title: string) => {
    e.stopPropagation()
    setMenuOpenId(null)
    setRenamingId(id)
    setRenameValue(title)
    setTimeout(() => renameInputRef.current?.select(), 0)
  }

  const commitRename = () => {
    const trimmed = renameValue.trim()
    if (trimmed && renamingId) updateConvTitle(renamingId, trimmed)
    setRenamingId(null)
  }

  const handleTogglePin = (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    setMenuOpenId(null)
    togglePin(id)
  }

  const handleDelete = (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    setMenuOpenId(null)
    deleteConversation(id)
  }

  return (
    <aside className={`flex flex-col bg-white border-r border-gray-200 ${className}`}>
      {/* 상단 헤더 */}
      <div className="p-3 border-b border-gray-200">
        <button
          onClick={handleNew}
          className="flex items-center gap-2 w-full bg-blue-600 hover:bg-blue-700 text-white rounded-lg px-3 py-2 text-sm font-medium transition-colors"
        >
          <Plus size={16} />
          새 대화
        </button>
      </div>

      {/* 대화 목록 */}
      <nav className="flex-1 overflow-y-auto p-2 space-y-0.5" ref={menuContainerRef}>
        {sorted.length === 0 && (
          <p className="text-xs text-gray-400 text-center py-4">대화가 없습니다.</p>
        )}
        {sorted.map(conv => {
          const isPending = pendingConvIds.includes(conv.id)
          const isRenaming = renamingId === conv.id
          const menuOpen = menuOpenId === conv.id
          const isActive = activeConvId === conv.id

          return (
            <div key={conv.id} className="relative">
              <div
                onClick={() => setActiveConv(conv.id)}
                className={`group flex items-center gap-1.5 rounded-lg px-2 py-2 cursor-pointer text-sm transition-colors ${
                  isActive
                    ? 'bg-blue-50 text-blue-700 font-medium'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
              >
                {/* 고정 핀 */}
                {conv.pinned && (
                  <Pin size={11} className="flex-shrink-0 text-blue-400 -rotate-45" />
                )}

                {/* 대화명 or 인라인 편집 */}
                {isRenaming ? (
                  <input
                    ref={renameInputRef}
                    value={renameValue}
                    onChange={e => setRenameValue(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') commitRename()
                      if (e.key === 'Escape') setRenamingId(null)
                    }}
                    onBlur={commitRename}
                    onClick={e => e.stopPropagation()}
                    className="flex-1 min-w-0 text-sm border border-blue-400 rounded px-1.5 py-0.5 focus:outline-none focus:ring-2 focus:ring-blue-500 text-gray-900 font-normal bg-white"
                  />
                ) : (
                  <span className="truncate flex-1 min-w-0">
                    {conv.title.length > 27 ? conv.title.slice(0, 27) + '…' : conv.title}
                  </span>
                )}

                {/* 우측 — 로딩 스피너 + 더보기 버튼 */}
                {!isRenaming && (
                  <div className="flex items-center gap-0.5 flex-shrink-0">
                    {isPending && (
                      <Loader2 size={13} className="text-blue-500 animate-spin" />
                    )}
                    <button
                      onClick={e => openMenu(e, conv.id)}
                      className={`p-0.5 rounded transition-colors ${
                        menuOpen
                          ? 'text-gray-700'
                          : 'text-gray-400 hover:text-gray-600'
                      }`}
                      title="더보기"
                    >
                      <MoreHorizontal size={15} />
                    </button>
                  </div>
                )}
              </div>

              {/* 드롭다운 */}
              {menuOpen && (
                <div className="absolute right-1 top-full z-50 mt-0.5 w-36 bg-white rounded-lg shadow-lg border border-gray-200 py-1 text-sm">
                  <button
                    onClick={e => startRename(e, conv.id, conv.title)}
                    className="flex items-center gap-2 w-full px-3 py-1.5 text-left text-gray-700 hover:bg-gray-100 transition-colors"
                  >
                    <Pencil size={13} />
                    이름 변경
                  </button>
                  <button
                    onClick={e => handleTogglePin(e, conv.id)}
                    className="flex items-center gap-2 w-full px-3 py-1.5 text-left text-gray-700 hover:bg-gray-100 transition-colors"
                  >
                    {conv.pinned ? <PinOff size={13} /> : <Pin size={13} />}
                    {conv.pinned ? '고정 해제' : '상단 고정'}
                  </button>
                  <hr className="my-1 border-gray-100" />
                  <button
                    onClick={e => handleDelete(e, conv.id)}
                    className="flex items-center gap-2 w-full px-3 py-1.5 text-left text-red-600 hover:bg-red-50 transition-colors"
                  >
                    <Trash2 size={13} />
                    삭제
                  </button>
                </div>
              )}
            </div>
          )
        })}
      </nav>

      {/* 어드민 버튼 */}
      {isAdmin && (
        <div className="p-3 border-t border-gray-200">
          <Link
            to="/admin"
            className="flex items-center gap-2 w-full text-gray-600 hover:bg-gray-100 rounded-lg px-3 py-2 text-sm transition-colors"
          >
            <Settings size={15} className="flex-shrink-0" />
            <span>관리자 설정</span>
          </Link>
        </div>
      )}
    </aside>
  )
}
