# Root 权限安装支持 / Root Installation Support

该项目现在支持在 Shizuku 不可用时使用 Root 权限作为后备方案来安装 APK 文件。

This project now supports using root privileges as a fallback when Shizuku is unavailable for APK installation.

## 工作原理 / How It Works

1. **主要方式**: 首先尝试使用 Shizuku 进行安装 / **Primary Method**: First attempts to use Shizuku for installation
2. **后备方案**: 如果 Shizuku 不可用，自动尝试使用 Root 权限 / **Fallback**: If Shizuku unavailable, automatically tries root privileges
3. **错误处理**: 提供清晰的错误信息说明失败原因 / **Error Handling**: Provides clear error messages explaining failure reasons

## Root 安装特性 / Root Installation Features

- ✅ 自动检测 Root 权限可用性 / Automatic root access detection
- ✅ 使用 `pm install` 命令进行安装 / Uses `pm install` command for installation  
- ✅ 支持替换和降级安装 / Supports replace and downgrade installation
- ✅ 临时文件安全处理 / Secure temporary file handling
- ✅ 完整的错误日志记录 / Comprehensive error logging

## 使用场景 / Use Cases

### 适用情况 / When to Use Root Installation:
- Shizuku 未安装 / Shizuku not installed
- Shizuku 服务未运行 / Shizuku service not running  
- Shizuku 权限被拒绝 / Shizuku permission denied
- 设备已 Root 但不想使用 Shizuku / Device rooted but don't want to use Shizuku

### 限制 / Limitations:
- 需要设备已获取 Root 权限 / Requires rooted device
- Split APK 安装仅支持完整安装模式 / Split APK installation only supports full install mode
- 依赖于 `pm` 命令的可用性 / Depends on `pm` command availability

## 错误码 / Error Codes

| 错误码 / Code | 描述 / Description |
|---------------|-------------------|
| `ABORT_SHIZUKU` | Shizuku 相关错误 / Shizuku-related errors |
| `ABORT_ROOT` | Root 权限不可用或安装失败 / Root unavailable or installation failed |

## 实现细节 / Implementation Details

### 核心组件 / Core Components:

1. **`RootInstaller.kt`** - Root 安装工具类 / Root installation utility class
   - `isRootAvailable()` - 检查 Root 权限 / Check root access
   - `installApk()` - 执行 APK 安装 / Execute APK installation
   - `hasInstallPermission()` - 验证安装权限 / Verify install permissions

2. **修改的文件 / Modified Files:**
   - `InstallRepository.kt` - 添加 Root 后备逻辑 / Added root fallback logic
   - `InstallStages.kt` - 新增 Root 错误码 / Added root error codes
   - `InstallErrorFragment.java` - Root 错误处理 / Root error handling
   - `strings.xml` - Root 错误信息 / Root error messages

### 安装流程 / Installation Flow:

```
1. 尝试 Shizuku / Try Shizuku
   ├─ 成功 → 使用 Shizuku 安装 / Success → Use Shizuku installation
   └─ 失败 → 检查 Root / Failed → Check Root
       ├─ Root 可用 → 使用 Root 安装 / Root available → Use root installation
       └─ Root 不可用 → 显示错误 / Root unavailable → Show error
```

## 安全考虑 / Security Considerations

- 临时文件存储在应用缓存目录 / Temp files stored in app cache directory
- 安装后自动清理临时文件 / Automatic cleanup of temp files after installation
- 仅在需要时请求 Root 权限 / Only requests root access when needed
- 不会持久化 Root 权限 / Does not persist root privileges

## 调试 / Debugging

可以通过以下日志标签查看详细信息 / Use these log tags for detailed information:

- `RootInstaller` - Root 安装相关日志 / Root installation logs
- `InstallRepository` - 安装流程日志 / Installation flow logs

使用命令查看日志 / View logs with command:
```bash
adb logcat -s RootInstaller,InstallRepository
```