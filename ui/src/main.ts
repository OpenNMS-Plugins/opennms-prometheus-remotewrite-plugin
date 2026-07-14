import App from './App.vue'
import { createApp } from 'vue'
import PrimeVue from 'primevue/config'

// Shared "se-*" style system, kept identical to the Alarm Notifications plugin so
// the two plugin UIs look and feel the same inside OpenNMS.
;(() => {
  if (document.getElementById('prometheusremotewrite-styles')) return
  const s = document.createElement('style')
  s.id = 'prometheusremotewrite-styles'
  s.textContent = [
    /* PrimeVue Dialog shell */
    '.p-dialog{background:var(--feather-surface,#fff)!important;border-radius:4px!important;box-shadow:0 8px 10px -5px rgba(0,0,0,.2),0 16px 24px 2px rgba(0,0,0,.14),0 6px 30px 5px rgba(0,0,0,.12)!important;border:none!important;overflow:hidden!important}',
    '.p-dialog .p-dialog-header{padding:.875rem 1.5rem!important;border-bottom:1px solid var(--feather-border-on-surface,rgba(10,12,27,.12))!important;font-family:var(--feather-font-family,system-ui)!important;font-size:1rem!important;font-weight:600!important;color:var(--feather-primary-text-on-surface,rgba(10,12,27,.9))!important;background:transparent!important}',
    '.p-dialog .p-dialog-content{padding:1.25rem 1.5rem!important;background:transparent!important;font-family:var(--feather-font-family,\'Open Sans\',system-ui,sans-serif)!important}',
    '.p-dialog .p-dialog-footer{padding:.75rem 1.5rem 1rem!important;display:flex!important;justify-content:flex-end!important;gap:.5rem!important;border-top:1px solid var(--feather-border-on-surface,rgba(10,12,27,.12))!important;background:transparent!important}',
    '.p-dialog-mask.p-component-overlay{background:rgba(10,12,27,.5)!important}',
    /* Button system */
    '.se-btn{display:inline-flex;align-items:center;justify-content:center;gap:.375rem;padding:0 1rem;height:2.25rem;border-radius:4px;font-size:.8125rem;font-weight:500;letter-spacing:.04em;text-transform:uppercase;cursor:pointer;border:none;outline:none;white-space:nowrap;font-family:var(--feather-font-family,system-ui,sans-serif);transition:box-shadow .2s,background .15s}',
    '.se-btn-primary{background:var(--feather-primary,#273180);color:#fff;box-shadow:0 3px 1px -2px rgba(0,0,0,.2),0 2px 2px 0 rgba(0,0,0,.14),0 1px 5px 0 rgba(0,0,0,.12)}',
    '.se-btn-primary:hover:not(:disabled){box-shadow:0 2px 4px -1px rgba(0,0,0,.2),0 4px 5px 0 rgba(0,0,0,.14),0 1px 10px 0 rgba(0,0,0,.12)}',
    '.se-btn-primary:disabled{opacity:.45;cursor:default;box-shadow:none}',
    '.se-btn-secondary{background:transparent;color:var(--feather-primary,#273180);border:1px solid var(--feather-primary,#273180)}',
    '.se-btn-secondary:hover:not(:disabled){background:rgba(39,49,128,.08)}',
    '.se-btn-secondary:disabled{opacity:.45;cursor:default}',
    '.se-btn-text{background:transparent;color:var(--feather-primary,#273180);border:none;box-shadow:none;padding:0 .75rem}',
    '.se-btn-text:hover:not(:disabled){background:rgba(39,49,128,.08)}',
    '.se-btn-icon{width:2.25rem;height:2.25rem;padding:0;border-radius:50%;background:transparent;border:none;color:var(--feather-secondary-text-on-surface,rgba(10,12,27,.7));cursor:pointer;display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;transition:background .15s,color .15s}',
    '.se-btn-icon:hover{background:rgba(10,12,27,.08)}',
    '.se-btn-icon.danger:hover{color:#b3261e;background:rgba(179,38,30,.1)}',
    /* Inputs */
    '.se-input{width:100%;box-sizing:border-box;padding:.625rem .75rem;border:1px solid rgba(10,12,27,.38);border-radius:4px;font-family:var(--feather-font-family,system-ui,sans-serif);font-size:1rem;color:var(--feather-primary-text-on-surface,rgba(10,12,27,.9));background:transparent;outline:none;transition:border-color .15s}',
    '.se-input:hover:not(:disabled){border-color:var(--feather-primary-text-on-surface,rgba(10,12,27,.9))}',
    '.se-input:focus{border-color:var(--feather-primary,#273180);border-width:2px;padding:.5625rem .6875rem}',
    '.se-input:disabled{opacity:.5;cursor:default}',
    '.se-input.error{border-color:#b3261e}',
    /* Autocomplete dropdown (Metric Explorer) */
    '.se-suggest{position:absolute;top:calc(100% + 2px);left:0;right:0;z-index:1000;background:var(--feather-surface,#fff);border:1px solid rgba(10,12,27,.18);border-radius:4px;box-shadow:0 4px 12px rgba(0,0,0,.15);max-height:260px;overflow-y:auto;margin:0;padding:.25rem 0;list-style:none}',
    '.se-suggest li{padding:.4rem .75rem;font-size:.8125rem;cursor:pointer;font-family:monospace;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
    '.se-suggest li:hover,.se-suggest li.active{background:rgba(39,49,128,.1)}',
    '.se-suggest li.se-suggest-note{padding:.4rem .75rem;font-size:.72rem;color:rgba(10,12,27,.5);cursor:default;font-family:var(--feather-font-family,system-ui,sans-serif);background:transparent}',
    /* Expansion icon */
    '.se-expansion-icon{transition:transform .28s ease-in-out;display:flex;align-items:center}',
    '.se-expansion-icon.rotated{transform:rotate(180deg)}',
    /* Table row hover */
    '.se-table tbody tr:hover td{background:rgba(10,12,27,.04)}',
    /* Spin animation for loading icons */
    '@keyframes pwspin{from{transform:rotate(0)}to{transform:rotate(360deg)}}',
    '.pw-spin{animation:pwspin 1s linear infinite}',
  ].join('')
  document.head.appendChild(s)
})()

// Expose the root component on window so OpenNMS can find and mount it.
// OpenNMS reads UIExtension.getExtensionId() = "prometheusremotewrite" to locate it here.
;(window as Record<string, unknown>)['prometheusremotewrite'] = App

// Full standalone mount only when running the Vite dev server
if (import.meta.env.MODE === 'development') {
  const app = createApp(App)
  app.use(PrimeVue, { ripple: true })
  app.mount('#app')
}
