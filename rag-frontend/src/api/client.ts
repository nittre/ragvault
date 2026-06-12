import axios from 'axios'

const apiClient = axios.create({ withCredentials: true })

apiClient.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 401 && !err.config?.url?.includes('/auth/login')) {
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export default apiClient
