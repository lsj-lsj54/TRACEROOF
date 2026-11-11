/**
 * 档案库分析页：档案库资源用量、分类、标签、大小、上传趋势与（管理员的）档案库排行
 * <p>
 * 图表用全局 echarts（index.html 里已引入 ECharts 与词云扩展）。
 * 支持两种视角：
 * 1）用户：从自己的档案库里选一个分析；
 * 2）管理员：可额外选择「全部档案库（汇总）」，并查看档案库使用排行。
 */
import { archiveApi, archiveAnalyzeApi } from '../api.js';
import {
  $, $$, toast, escapeHtml, formatSize, renderEmpty, state,
  revealOnScroll, attachRippleAll,
} from '../ui.js';

/** 图表主色板，与深色主题保持一致观感（提亮保证深底上的对比度） */
const PALETTE = ['#4d8dff', '#2dd4bf', '#fbbf24', '#f06a6f', '#b18cff', '#38bdf8', '#a3e635', '#f472b6'];

/** 时间维度文案与值，折线图切换用 */
const DIMENSIONS = [
  { value: 'day', text: '按天' },
  { value: 'week', text: '按周' },
  { value: 'month', text: '按月' },
];

/** 当前分析上下文 */
let current = {
  /** 档案库 id；null 表示全部档案库（仅管理员可选） */
  archiveId: null,
  timeDimension: 'day',
};

/** 已创建的 echarts 实例，destroy 时统一释放 */
let charts = [];

/** 窗口尺寸变化时让所有图表自适应 */
function handleResize() {
  charts.forEach((chart) => chart.resize());
}

/** 是否具备图表能力（ECharts 已加载且非测试桩环境） */
function hasEcharts() {
  return typeof window !== 'undefined' && !!window.echarts;
}

/**
 * 在指定容器上渲染图表并登记实例
 *
 * @param {HTMLElement} container 容器（需要有确定高度）
 * @param {object}      option    echarts option
 * @returns {object|null} 实例；ECharts 不可用或渲染异常（如词云扩展缺失）时返回 null
 */
function mountChart(container, option) {
  if (!hasEcharts() || !container) {
    return null;
  }
  try {
    // 深色主题：ECharts 内置 dark 主题让文字/轴线/图例变浅，背景再覆盖为透明以贴合卡片底色
    const chart = window.echarts.init(container, 'dark');
    chart.setOption({ backgroundColor: 'transparent' });
    chart.setOption(option);
    charts.push(chart);
    return chart;
  } catch (e) {
    // 词云等扩展缺失、容器异常等情况：返回 null，由调用方决定降级方案
    return null;
  }
}

/* ------------------------------ 各图表的 option ------------------------------ */

/** 资源用量仪表盘（容量 / 数量两个表盘） */
function usageOption(title, ratio, subText) {
  return {
    series: [{
      type: 'gauge',
      startAngle: 220,
      endAngle: -40,
      min: 0,
      max: 100,
      progress: { show: true, width: 14 },
      axisLine: { lineStyle: { width: 14 } },
      axisTick: { show: false },
      splitLine: { length: 6 },
      axisLabel: { show: false },
      pointer: { show: false },
      anchor: { show: false },
      detail: {
        valueAnimation: true,
        formatter: '{value}%',
        fontSize: 22,
        offsetCenter: [0, '10%'],
      },
      title: { offsetCenter: [0, '42%'], fontSize: 12 },
      data: [{ value: Math.min(ratio ?? 0, 100), name: subText }],
      itemStyle: { color: ratio >= 90 ? '#f06a6f' : PALETTE[0] },
    }],
    title: { text: title, left: 'center', top: 0, textStyle: { fontSize: 14 } },
  };
}

/** 分类分组条形图：同一分类同时展示数量与总大小（双 y 轴） */
function categoryOption(rows) {
  const categories = rows.map((row) => row.category);
  return {
    color: [PALETTE[0], PALETTE[2]],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { top: 0 },
    grid: { left: 48, right: 56, bottom: 32, top: 36 },
    xAxis: { type: 'category', data: categories, axisLabel: { interval: 0, rotate: categories.length > 6 ? 30 : 0 } },
    yAxis: [
      { type: 'value', name: '数量（张）' },
      { type: 'value', name: '大小（MB）', splitLine: { show: false } },
    ],
    series: [
      { name: '影像数量', type: 'bar', data: rows.map((row) => row.count), barMaxWidth: 28 },
      {
        name: '总大小',
        type: 'bar',
        yAxisIndex: 1,
        data: rows.map((row) => Math.round((row.totalSize / 1048576) * 100) / 100),
        barMaxWidth: 28,
      },
    ],
  };
}

/** 标签词云；词云扩展不可用时降级为横向条形图 */
function tagOption(rows) {
  const data = rows.map((row) => ({ name: row.tag, value: row.count }));
  return {
    tooltip: { show: true },
    series: [{
      type: 'wordCloud',
      shape: 'circle',
      width: '92%',
      height: '92%',
      sizeRange: [14, 48],
      rotationRange: [0, 0],
      gridSize: 8,
      textStyle: {
        fontFamily: 'inherit',
        color: () => PALETTE[Math.floor(Math.random() * PALETTE.length)],
      },
      data,
    }],
  };
}

/** 标签条形图降级版 */
function tagBarOption(rows) {
  const sorted = [...rows].reverse();
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 90, right: 32, top: 12, bottom: 24 },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: { type: 'category', data: sorted.map((row) => row.tag) },
    series: [{
      name: '关联影像数',
      type: 'bar',
      data: sorted.map((row) => row.count),
      itemStyle: { color: PALETTE[1] },
      barMaxWidth: 18,
      label: { show: true, position: 'right' },
    }],
  };
}

/** 大小分段饼图 */
function sizeOption(rows) {
  return {
    color: PALETTE,
    tooltip: { trigger: 'item', formatter: '{b}：{c} 张（{d}%）' },
    legend: { bottom: 0 },
    series: [{
      name: '影像大小',
      type: 'pie',
      radius: ['38%', '66%'],
      center: ['50%', '46%'],
      avoidLabelOverlap: true,
      label: { formatter: '{b}\n{c} 张' },
      data: rows.map((row) => ({ name: row.sizeRange, value: row.count })),
    }],
  };
}

/** 上传趋势折线图 */
function trendOption(rows) {
  return {
    color: [PALETTE[0]],
    tooltip: { trigger: 'axis' },
    grid: { left: 48, right: 32, bottom: 32, top: 24 },
    xAxis: { type: 'category', data: rows.map((row) => row.date), boundaryGap: false },
    yAxis: { type: 'value', minInterval: 1 },
    series: [{
      name: '上传数量',
      type: 'line',
      smooth: true,
      data: rows.map((row) => row.count),
      areaStyle: { opacity: 0.12 },
    }],
  };
}

/** 档案库使用排行柱状图（管理员） */
function rankOption(rows) {
  const sorted = [...rows].reverse();
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const item = params[0];
        const row = sorted[item.dataIndex];
        return `${escapeHtml(row.archiveName)}<br/>已用 ${formatSize(row.totalSize)}（${row.sizeRatio}%）<br/>影像 ${row.totalCount} 张`;
      },
    },
    grid: { left: 110, right: 48, top: 12, bottom: 24 },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: sorted.map((row) => row.archiveName),
      axisLabel: { width: 90, overflow: 'truncate' },
    },
    series: [{
      name: '已用容量（MB）',
      type: 'bar',
      data: sorted.map((row) => Math.round((row.totalSize / 1048576) * 100) / 100),
      itemStyle: { color: PALETTE[3] },
      barMaxWidth: 18,
      label: { show: true, position: 'right', formatter: '{c} MB' },
    }],
  };
}

/* ------------------------------ 数据加载 ------------------------------ */

/**
 * 通用加载：调用接口、渲染或显示错误
 *
 * @param {HTMLElement} el      视图容器
 * @param {string}      boxId   图表容器 id
 * @param {Function}    fetcher 返回接口 Promise
 * @param {Function}    toOption 把数据转成 echarts option（返回 null 表示无数据）
 * @param {string}      emptyText 无数据提示
 */
async function loadChart(el, boxId, fetcher, toOption, emptyText) {
  const box = $(`#${boxId}`, el);
  if (!box) {
    return;
  }
  try {
    const rows = await fetcher();
    const option = rows && rows.length > 0 ? toOption(rows) : null;
    if (!option) {
      box.innerHTML = `<div class="chart-empty">${escapeHtml(emptyText)}</div>`;
      return;
    }
    const chart = mountChart(box, option);
    if (!chart) {
      // ECharts 不可用（异常环境），至少提示无法出图
      box.innerHTML = '<div class="chart-empty">图表组件未加载，无法展示</div>';
    }
  } catch (e) {
    box.innerHTML = `<div class="chart-empty">${escapeHtml(e.message)}</div>`;
  }
}

/** 拉取并渲染全部分析图表 */
function loadAll(el) {
  const scope = { archiveId: current.archiveId };

  loadChart(el, 'usage-size-box',
    () => archiveAnalyzeApi.usage(scope).then((vo) => [vo]),
    (rows) => usageOption('容量使用', rows[0].sizeRatio,
      scope.archiveId ? '档案库容量' : '全系统容量'),
    '暂无用量数据');

  loadChart(el, 'usage-count-box',
    () => archiveAnalyzeApi.usage(scope).then((vo) => [vo]),
    (rows) => usageOption('数量使用', rows[0].countRatio,
      scope.archiveId ? '档案库影像数' : '全系统影像数'),
    '暂无用量数据');

  loadChart(el, 'category-box',
    () => archiveAnalyzeApi.category(scope),
    categoryOption,
    '还没有影像或未设置分类');

  // 标签：优先词云，词云扩展缺失时降级为条形图
  loadTagChart(el, scope);

  loadChart(el, 'size-box',
    () => archiveAnalyzeApi.size(scope),
    sizeOption,
    '还没有影像');

  loadChart(el, 'trend-box',
    () => archiveAnalyzeApi.trend({ ...scope, timeDimension: current.timeDimension }),
    trendOption,
    '还没有上传记录');

  // 档案库使用排行：仅管理员且处于全档案库视角时展示
  const rankCard = $('#rank-card', el);
  if (rankCard) {
    const visible = state.isAdmin() && current.archiveId == null;
    rankCard.hidden = !visible;
    if (visible) {
      loadChart(el, 'rank-box',
        () => archiveAnalyzeApi.rank({ limit: 10 }),
        rankOption,
        '还没有档案库数据');
    }
  }
}

/**
 * 标签词云加载：优先词云，词云扩展不可用时降级为横向条形图
 */
async function loadTagChart(el, scope) {
  const box = $('#tag-box', el);
  if (!box) {
    return;
  }
  try {
    const rows = await archiveAnalyzeApi.tag(scope);
    if (!rows || rows.length === 0) {
      box.innerHTML = '<div class="chart-empty">还没有标签，上传影像时可以给影像打标签</div>';
      return;
    }
    let chart = mountChart(box, tagOption(rows));
    if (!chart) {
      chart = mountChart(box, tagBarOption(rows));
    }
    if (!chart) {
      box.innerHTML = '<div class="chart-empty">图表组件未加载，无法展示</div>';
    }
  } catch (e) {
    box.innerHTML = `<div class="chart-empty">${escapeHtml(e.message)}</div>`;
  }
}

/* ------------------------------ 档案库选择 ------------------------------ */

/** 加载档案库选择器：普通用户列出自己的档案库，管理员额外有「全部档案库」 */
async function loadArchiveOptions(el, preselectId) {
  const select = $('#archive-select', el);
  if (!select) {
    return;
  }
  try {
    const page = await archiveApi.myListVOByPage({ current: 1, pageSize: 100 });
    const archives = (page && page.records) || [];

    const options = [];
    if (state.isAdmin()) {
      options.push('<option value="">全部档案库（公共案例库 + 所有档案库）</option>');
    }
    archives.forEach((archive) => {
      options.push(`<option value="${archive.id}">${escapeHtml(archive.archiveName)}</option>`);
    });

    if (archives.length === 0 && !state.isAdmin()) {
      select.innerHTML = '<option value="">暂无档案库</option>';
      $('#analyze-body', el).innerHTML = renderEmpty('你还没有档案库，先到「我的档案库」创建一个吧');
      return;
    }

    select.innerHTML = options.join('');
    // 优先用路由参数指定的档案库，其次保持全档案库视角（管理员），否则选第一个档案库
    const initial = preselectId || select.options[0].value;
    select.value = initial;
    current.archiveId = initial === '' ? null : initial;
    loadAll(el);
  } catch (e) {
    toast(e.message, 'error');
  }
}

/* ------------------------------ 视图 ------------------------------ */

export default {
  /**
   * 渲染分析页
   *
   * @param {HTMLElement} el    视图容器
   * @param {object}      query 路由查询参数，支持 ?archiveId= 指定档案库
   */
  render(el, query = {}) {
    current = { archiveId: null, timeDimension: 'day' };
    charts = [];

    el.innerHTML = `
      <section class="analyze-toolbar">
        <h2 class="page-title">档案库分析</h2>
        <select id="archive-select" class="input input-select"></select>
      </section>
      <div id="analyze-body">
        <section class="chart-grid">
          <div class="chart-card reveal">
            <h3 class="chart-title">资源用量 · 容量</h3>
            <div id="usage-size-box" class="chart-box chart-box-gauge"></div>
          </div>
          <div class="chart-card reveal">
            <h3 class="chart-title">资源用量 · 数量</h3>
            <div id="usage-count-box" class="chart-box chart-box-gauge"></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal">
            <h3 class="chart-title">影像分类分布</h3>
            <div id="category-box" class="chart-box"></div>
          </div>
        </section>
        <section class="chart-grid">
          <div class="chart-card reveal">
            <h3 class="chart-title">标签词云</h3>
            <div id="tag-box" class="chart-box"></div>
          </div>
          <div class="chart-card reveal">
            <h3 class="chart-title">影像大小分布</h3>
            <div id="size-box" class="chart-box"></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal">
            <h3 class="chart-title">
              上传行为趋势
              <span class="dimension-switch" id="dimension-switch">
                ${DIMENSIONS.map((item) => `
                  <button class="btn btn-sm ${item.value === current.timeDimension ? 'btn-primary' : ''}"
                          data-dim="${item.value}" type="button">${item.text}</button>
                `).join('')}
              </span>
            </h3>
            <div id="trend-box" class="chart-box chart-box-trend"></div>
          </div>
        </section>
        <section class="chart-grid chart-grid-wide">
          <div class="chart-card chart-card-wide reveal" id="rank-card" hidden>
            <h3 class="chart-title">档案库使用排行（Top 10）</h3>
            <div id="rank-box" class="chart-box chart-box-trend"></div>
          </div>
        </section>
      </div>`;

    revealOnScroll(el);
    attachRippleAll(el);

    if (!hasEcharts()) {
      $('#analyze-body', el).innerHTML = renderEmpty('图表组件（ECharts）未加载，请检查网络或 lib 目录');
      return;
    }

    // 档案库切换：重新分析所选档案库
    $('#archive-select', el).addEventListener('change', (e) => {
      current.archiveId = e.target.value === '' ? null : e.target.value;
      loadAll(el);
    });

    // 趋势维度切换：只刷新折线图
    $('#dimension-switch', el).addEventListener('click', (e) => {
      const btn = e.target.closest('[data-dim]');
      if (!btn) {
        return;
      }
      current.timeDimension = btn.dataset.dim;
      $$('#dimension-switch .btn', el).forEach((item) => {
        item.classList.toggle('btn-primary', item === btn);
      });
      loadChart(el, 'trend-box',
        () => archiveAnalyzeApi.trend({ archiveId: current.archiveId, timeDimension: current.timeDimension }),
        trendOption,
        '还没有上传记录');
    });

    loadArchiveOptions(el, query.archiveId);

    // 窗口尺寸变化时让图表自适应，destroy 时移除
    window.addEventListener('resize', handleResize);
  },

  /** 释放所有 echarts 实例与 resize 监听 */
  destroy() {
    window.removeEventListener('resize', handleResize);
    charts.forEach((chart) => chart.dispose());
    charts = [];
  },
};
