import React, { useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Settings2, LogOut, ChevronDown, Pencil } from 'lucide-react'
import { v4 as uuidv4 } from 'uuid'
import { useAuthStore } from '../stores/authStore'
import { useChatStore } from '../stores/chatStore'
import { useParamStore } from '../stores/paramStore'
import { sendMessage, uploadFile } from '../api/chat'
import { generateChatTitle } from '../api/chatTitle'
import ConversationList from '../components/chat/ConversationList'
import MessageList from '../components/chat/MessageList'
import MessageInput from '../components/chat/MessageInput'
import ParamSidePanel from '../components/chat/ParamSidePanel'
import { logout } from '../api/auth'
import type { Message } from '../types'

export default function ChatPage() {
  const navigate = useNavigate()
  const { email, clearAuth } = useAuthStore()
  const {
    conversations,
    messages,
    activeConvId,
    pendingConvIds,
    pendingStartTimes,
    createConversation,
    setActiveConv,
    addMessage,
    updateConvTitle,
    setPending,
  } = useChatStore()

  const activeConv = conversations.find(c => c.id === activeConvId) ?? null
  const { params, systemPrompt, isPanelOpen, togglePanel } = useParamStore()

  const isLoading = activeConvId ? pendingConvIds.includes(activeConvId) : false
  const loadingStartTime = activeConvId ? (pendingStartTimes[activeConvId] ?? null) : null
  const [isSidebarOpen, setIsSidebarOpen] = useState(true)
  const [isUploading, setIsUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)

  // 대화방 타이틀 인라인 편집
  const [isTitleEditing, setIsTitleEditing] = useState(false)
  const [editTitle, setEditTitle] = useState('')
  const titleInputRef = useRef<HTMLInputElement>(null)

  const startTitleEdit = () => {
    setEditTitle(activeConv?.title ?? '')
    setIsTitleEditing(true)
    setTimeout(() => titleInputRef.current?.select(), 0)
  }

  const commitTitleEdit = () => {
    const trimmed = editTitle.trim()
    if (trimmed && activeConvId) updateConvTitle(activeConvId, trimmed)
    setIsTitleEditing(false)
  }

  const handleTitleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') commitTitleEdit()
    if (e.key === 'Escape') setIsTitleEditing(false)
  }

  // 첫 번째 메시지 기준으로 LLM에 대화방 제목 자동 생성 요청 (RAG 우회, /api/v1/chat/title)
  const generateTitle = async (convId: string, firstUserMsg: string) => {
    try {
      const title = await generateChatTitle(firstUserMsg)
      if (title.trim()) updateConvTitle(convId, title.trim())
    } catch {
      // 타이틀 생성 실패 시 무시 (기본 제목 유지)
    }
  }

  const currentMessages = activeConvId ? (messages[activeConvId] ?? []) : []

  const handleSend = async (rawText: string, files: File[], images: string[]) => {
    // 슬래시 커맨드 파싱 (/rag, /web, /sql)
    let routingHint: string | undefined
    let text = rawText
    if (rawText.startsWith('/rag ') || rawText === '/rag') {
      routingHint = 'FORCE_RAG'
      text = rawText.replace(/^\/rag\s*/, '')
    } else if (rawText.startsWith('/web ') || rawText === '/web') {
      routingHint = 'FORCE_WEB'
      text = rawText.replace(/^\/web\s*/, '')
    } else if (rawText.startsWith('/sql ') || rawText === '/sql') {
      routingHint = 'FORCE_SQL'
      text = rawText.replace(/^\/sql\s*/, '')
    }

    // 대화 없으면 자동 생성
    let convId = activeConvId
    if (!convId) {
      convId = createConversation()
      setActiveConv(convId)
    }

    // 파일 업로드
    const fileIds: string[] = []
    const failedFiles: string[] = []
    if (files.length > 0) {
      setIsUploading(true)
      setUploadError(null)
      for (const file of files) {
        try {
          const res = await uploadFile(file)
          fileIds.push(res.id)
        } catch {
          failedFiles.push(file.name)
        }
      }
      setIsUploading(false)
      if (failedFiles.length > 0) {
        setUploadError(`업로드 실패: ${failedFiles.join(', ')}`)
        if (fileIds.length === 0 && !text.trim()) return
      }
    }

    // user 메시지 추가
    const userMsg: Message = {
      id: uuidv4(),
      role: 'user',
      content: text,
      timestamp: Date.now(),
      routingHint: routingHint as Message['routingHint'],
      attachedFileNames: files.length > 0 ? files.map(f => f.name) : undefined,
    }
    addMessage(convId, userMsg)

    // 첫 번째 user 메시지인지 확인 (응답 후 LLM 제목 생성용)
    const existingUserMessages = (messages[convId] ?? []).filter(m => m.role === 'user')
    const isFirstMessage = existingUserMessages.length === 0

    // 요청 메시지 구성
    const allMessages = [...(messages[convId] ?? []), userMsg]
    const historyMessages = allMessages
      .filter(m => m.role !== 'system')
      .map(m => ({ role: m.role, content: m.content }))

    const requestMessages: Array<{ role: string; content: string }> = []
    if (systemPrompt.trim()) {
      requestMessages.push({ role: 'system', content: systemPrompt })
    }
    requestMessages.push(...historyMessages)

    const startTime = Date.now()
    setPending(convId, true)
    try {
      const res = await sendMessage({
        model: 'rag-auto',
        messages: requestMessages,
        rag_params: params,
        file_ids: fileIds.length > 0 ? fileIds : undefined,
        images: images.length > 0 ? images : undefined,
        routing_hint: routingHint,
      })

      const choice = res.choices[0]
      const assistantMsg: Message = {
        id: uuidv4(),
        role: 'assistant',
        content: choice.message.content,
        citations: res.citations,
        intent: res.intent,
        responseId: res.responseId,
        generatedSql: res.generatedSql ?? null,
        sourceUrls: res.sourceUrls ?? null,
        timestamp: Date.now(),
        elapsedMs: Date.now() - startTime,
      }
      addMessage(convId, assistantMsg)

      // 첫 번째 대화면 LLM으로 제목 자동 생성 (비동기, 응답 지연 무시)
      if (isFirstMessage && text.trim()) {
        generateTitle(convId, text.trim())
      }
    } catch (err: unknown) {
      const errorContent = err instanceof Error ? err.message : '응답 생성에 실패했습니다.'
      const errorMsg: Message = {
        id: uuidv4(),
        role: 'assistant',
        content: `오류가 발생했습니다: ${errorContent}`,
        timestamp: Date.now(),
        elapsedMs: Date.now() - startTime,
      }
      addMessage(convId, errorMsg)
    } finally {
      setPending(convId, false)
    }
  }

  const handleLogout = async () => {
    try {
      await logout()
    } catch {
      // ignore
    }
    clearAuth()
    navigate('/login')
  }

  return (
    <div className="flex h-screen bg-gray-50 overflow-hidden">
      {/* 왼쪽 사이드바 */}
      {isSidebarOpen && (
        <ConversationList className="w-64 flex-shrink-0" />
      )}

      {/* 메인 영역 */}
      <div className="flex flex-col flex-1 min-w-0">
        {/* 상단 헤더 */}
        <header className="relative flex items-center justify-between px-4 py-3 bg-white border-b border-gray-200 flex-shrink-0">
          <div className="flex items-center gap-2 min-w-0">
            <button
              onClick={() => setIsSidebarOpen(prev => !prev)}
              className="p-1.5 text-gray-400 hover:text-gray-700 rounded-lg hover:bg-gray-100 transition-colors flex-shrink-0"
              title="사이드바 토글"
            >
              <ChevronDown
                size={18}
                className={`transition-transform ${isSidebarOpen ? 'rotate-90' : '-rotate-90'}`}
              />
            </button>
            <span className="font-semibold text-gray-800 flex-shrink-0">RagVault</span>
          </div>

          {/* 대화방 타이틀 — 중앙 고정 */}
          {activeConv && (
            <div className="absolute left-1/2 -translate-x-1/2 max-w-sm w-full flex justify-center px-2">
              {isTitleEditing ? (
                <input
                  ref={titleInputRef}
                  value={editTitle}
                  onChange={e => setEditTitle(e.target.value)}
                  onKeyDown={handleTitleKeyDown}
                  onBlur={commitTitleEdit}
                  className="w-full text-center text-sm font-medium text-gray-900 border border-blue-400 rounded-lg px-3 py-1 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              ) : (
                <button
                  onClick={startTitleEdit}
                  className="group flex items-center gap-1.5 text-sm font-medium text-gray-700 hover:text-gray-900 rounded-lg px-3 py-1 hover:bg-gray-100 transition-colors max-w-full"
                  title="클릭하여 제목 편집"
                >
                  <span className="truncate">{activeConv.title}</span>
                  <Pencil size={12} className="flex-shrink-0 text-gray-400 opacity-0 group-hover:opacity-100 transition-opacity" />
                </button>
              )}
            </div>
          )}

          <div className="flex items-center gap-2 min-w-0">
            <span className="text-xs text-gray-400 hidden sm:block">{email}</span>
            <button
              onClick={togglePanel}
              className={`p-1.5 rounded-lg transition-colors ${
                isPanelOpen
                  ? 'bg-blue-100 text-blue-600'
                  : 'text-gray-400 hover:text-gray-700 hover:bg-gray-100'
              }`}
              title="파라미터 설정"
            >
              <Settings2 size={18} />
            </button>
            <button
              onClick={handleLogout}
              className="p-1.5 text-gray-400 hover:text-gray-700 rounded-lg hover:bg-gray-100 transition-colors"
              title="로그아웃"
            >
              <LogOut size={18} />
            </button>
          </div>
        </header>

        {/* 메시지 영역 */}
        <MessageList messages={currentMessages} isLoading={isLoading} loadingStartTime={loadingStartTime} />

        {/* 업로드 에러 배너 */}
        {uploadError && (
          <div className="mx-4 mb-1 px-3 py-2 bg-red-50 border border-red-200 text-red-600 text-xs rounded-lg flex items-center justify-between">
            <span>{uploadError}</span>
            <button onClick={() => setUploadError(null)} className="ml-3 text-red-400 hover:text-red-600">×</button>
          </div>
        )}

        {/* 입력 영역 */}
        <MessageInput
          onSend={handleSend}
          disabled={isLoading || isUploading}
          history={currentMessages.filter(m => m.role === 'user').map(m => m.content)}
        />
      </div>

      {/* 오른쪽 파라미터 패널 */}
      {isPanelOpen && <ParamSidePanel />}
    </div>
  )
}
