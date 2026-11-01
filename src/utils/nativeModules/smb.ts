/**
 * SMB 原生能力封装（仅 Android 可用）
 * 原生实现见 android/app/src/main/java/cn/toside/music/mobile/smb
 */
import { DeviceEventEmitter, NativeModules, Platform } from 'react-native'

export interface ISmbConfig {
  /** 服务器地址/IP */
  host: string
  /** 端口，默认 445 */
  port?: number
  /** 共享名，例如 music */
  share: string
  username?: string
  password?: string
  /** 域（可留空） */
  domain?: string
}

export interface ISmbFileItem {
  name: string
  path: string
  isDirectory: boolean
  size: number
  lastModified?: number
}

export interface ISmbStat {
  exists: boolean
  isDirectory: boolean
  size: number
}

interface INativeSmbModule {
  testConnection(config: Record<string, any>): Promise<any>
  list(config: Record<string, any>, path: string): Promise<ISmbFileItem[]>
  stat(config: Record<string, any>, path: string): Promise<ISmbStat>
  exists(config: Record<string, any>, path: string): Promise<boolean>
  mkdir(config: Record<string, any>, path: string): Promise<boolean>
  readText(config: Record<string, any>, path: string): Promise<string>
  writeText(config: Record<string, any>, path: string, content: string): Promise<boolean>
  uploadFile(config: Record<string, any>, localPath: string, remotePath: string, taskId: string): Promise<boolean>
  downloadFile(config: Record<string, any>, remotePath: string, localPath: string, taskId: string): Promise<number>
  delete(config: Record<string, any>, path: string): Promise<boolean>
  closeConnection(): Promise<boolean>
  startBackgroundService(title: string, text: string): Promise<boolean>
  updateBackgroundService(title: string, text: string, current: number, total: number): Promise<boolean>
  stopBackgroundService(): Promise<boolean>
}

const NativeSmb: INativeSmbModule | undefined = NativeModules.SmbModule

export const smbSupported = Platform.OS === 'android' && !!NativeSmb

function assertAvailable() {
  if (!NativeSmb) throw new Error('当前平台不支持 SMB（仅 Android 可用）')
}

function normalizeConfig(config: ISmbConfig) {
  return {
    host: (config.host ?? '').trim(),
    port: config.port && config.port > 0 ? config.port : 445,
    share: (config.share ?? '').trim().replace(/^[/\\]+|[/\\]+$/g, ''),
    username: config.username ?? '',
    password: config.password ?? '',
    domain: config.domain ?? '',
  }
}

/** 拼接远程路径，自动处理分隔符 */
export function joinPath(...parts: string[]) {
  return parts
    .filter(it => typeof it === 'string' && it.length > 0)
    .map(it => it.replace(/\\/g, '/').replace(/^[/]+|[/]+$/g, ''))
    .filter(it => it.length > 0)
    .join('/')
}

function randomTaskId() {
  return `smb_${Date.now()}_${Math.floor(Math.random() * 100000)}`
}

async function withProgress<T>(
  onProgress: ((transferred: number, total: number) => void) | undefined,
  task: (taskId: string) => Promise<T>,
): Promise<T> {
  const taskId = randomTaskId()
  if (!onProgress || !NativeSmb) return task(taskId)

  // 原生模块通过 RCTDeviceEventEmitter 发送 SmbProgress，JS 侧用 DeviceEventEmitter 接收
  const subscription = DeviceEventEmitter.addListener('SmbProgress', (event: any) => {
    if (event?.taskId !== taskId) return
    try {
      onProgress(Number(event.transferred ?? 0), Number(event.total ?? 0))
    } catch {
      // 进度回调异常不影响传输
    }
  })
  try {
    return await task(taskId)
  } finally {
    subscription.remove()
  }
}

const SmbClient = {
  supported: smbSupported,

  /** 测试连接 */
  async testConnection(config: ISmbConfig) {
    assertAvailable()
    return NativeSmb!.testConnection(normalizeConfig(config))
  },

  async list(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.list(normalizeConfig(config), path)
  },

  async stat(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.stat(normalizeConfig(config), path)
  },

  async exists(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.exists(normalizeConfig(config), path)
  },

  async mkdir(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.mkdir(normalizeConfig(config), path)
  },

  async readText(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.readText(normalizeConfig(config), path)
  },

  async writeText(config: ISmbConfig, path: string, content: string) {
    assertAvailable()
    return NativeSmb!.writeText(normalizeConfig(config), path, content)
  },

  async uploadFile(
    config: ISmbConfig,
    localPath: string,
    remotePath: string,
    onProgress?: (transferred: number, total: number) => void,
  ) {
    assertAvailable()
    return withProgress(onProgress, taskId =>
      NativeSmb!.uploadFile(normalizeConfig(config), localPath, remotePath, taskId),
    )
  },

  async downloadFile(
    config: ISmbConfig,
    remotePath: string,
    localPath: string,
    onProgress?: (transferred: number, total: number) => void,
  ) {
    assertAvailable()
    return withProgress(onProgress, taskId =>
      NativeSmb!.downloadFile(normalizeConfig(config), remotePath, localPath, taskId),
    )
  },

  async delete(config: ISmbConfig, path: string) {
    assertAvailable()
    return NativeSmb!.delete(normalizeConfig(config), path)
  },

  async closeConnection() {
    if (!NativeSmb) return
    try {
      await NativeSmb.closeConnection()
    } catch {}
  },

  /** 启动前台服务（备份切后台也能继续） */
  async startBackgroundService(title: string, text: string) {
    if (!NativeSmb) return false
    try {
      return await NativeSmb.startBackgroundService(title, text)
    } catch {
      return false
    }
  },

  async updateBackgroundService(title: string, text: string, current: number, total: number) {
    if (!NativeSmb) return false
    try {
      return await NativeSmb.updateBackgroundService(title, text, current, total)
    } catch {
      return false
    }
  },

  async stopBackgroundService() {
    if (!NativeSmb) return false
    try {
      return await NativeSmb.stopBackgroundService()
    } catch {
      return false
    }
  },
}

export default SmbClient
