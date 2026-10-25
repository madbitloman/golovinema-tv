const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const code = fs.readFileSync('android/src/local/tv/streamtest/SourceBrowserActivity.java', 'utf8');
test('browser seek moves 30 seconds and clamps to the available media range', () => {
  const body = code.split('private void controlMedia(int seconds) {')[1].split('private void chooseVideoServer')[0];
  const strings = [...body.matchAll(/"(?:\\.|[^"\\])*"/g)].map(m => JSON.parse(m[0]));
  const run = seconds => new Function('document', 'return ' + strings[4] + strings[1] + seconds + strings[2] + strings[3] + strings[5]);
  const video = {currentTime:50, seekable:{length:1,start:()=>0,end:()=>100}};
  const document = {querySelector:()=>video};
  assert.equal(run(30)(document),true); assert.equal(video.currentTime,80);
  run(30)(document); assert.equal(video.currentTime,100);
  video.currentTime=10; run(-30)(document); assert.equal(video.currentTime,0);
  video.seekable.length=0; assert.equal(run(30)(document),false);
  assert.equal(run(30)({querySelector:()=>null}),false);
});
