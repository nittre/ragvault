import apiClient from './client'

export const generateChatTitle = (userMessage: string): Promise<string> =>
  apiClient
    .post<{ title?: string; error?: string }>('/api/v1/chat/title', { userMessage })
    .then(r => r.data.title ?? '')
