# System Design Architecture Board

## 요구사항
- 게시글
  - 게시글 조회, 게시글 생성, 게시글 수정, 게시글 삭제 API
  - 게시글 목록 조회 API(게시판별 최신순)
    - 페이지 번호, 무한 스크롤
- 댓글
  - 댓글 조회, 댓글 생성, 댓글 삭제 API
  - 댓글 목록 조회 API(계층별 오래된 순)
    - 최대 2 뎁스
    - 무한 뎁스
  - 페이지 번호
    - 인접 리스트 방식
  - 무한 스크롤
    - 경로 열거 방식
  - 계층형 대댓글
    - 하위 댓글 X => 댓글 즉시 삭제
    - 하위 댓글 O => 댓글 삭제 표시
- 좋아요
  - 각 사용자는 각 게시글에 1회 좋아요를 누를 수 있다.
    - 각 사용자는 좋아요와 취소가 가능하다.
    - 유니크 인덱스(게시글 ID + 사용자 ID)를 사용하면 구현이 가능하다.
  - 좋아요 수
- 조회 수
  - 조회 수 어뷰징 방지
    - 각 사용자는 게시글 1개 당 10분에 1번 조회수 집계 => 10분동안 100번 조회 시, 1번만 집계
- 인기글
  - 일 단위로 상위 10건 인기글 선정
    - 매일 오전 1시 업데이트
    - 좋아요 수/댓글 수/조회 수 기반 점수 계산 => 점수 = (좋아요 수 * 3) + (댓글 수 * 2) + (조회 수 * 1)
  - 최근 7일 인기글 내역 조회
- 게시글 조회 최적화
  - 게시글 단건 조회 최적화
  - 게시글 목록 조회 최적화
  - 캐시 최적화 전략(조회에 최적화된 캐시 전략 구성)

## 테이블 설계
### article
- article_id | BIGINT | PK
- title | VARCHAR(100) | 제목
- content | VARCHAR(3000) | 내용
- board_id | BIGINT | 게시판 ID(Shard Key)
- writer_id | BIGINT | 작성자 ID
- created_at | DATETIME | 생성시간
- modified_at | DATETIME | 수정시간

### comment
- comment_id | BIGINT | PK
- content | VARCHAR(3000) | 내용
- article_id | BIGINT | 게시글 ID(Shard Key)
- parent_comment_id | BIGINT | 상위 댓글 ID
- writer_id | BIGINT | 작성자 ID
- deleted | BOOL | 삭제여부
- created_at | DATETIME | 생성시간

### comment_v2
- comment_id | BIGINT | PK
- content | VARCHAR(3000) | 내용
- article_id | BIGINT | 게시글 ID(Shard Key)
- writer_id | BIGINT | 작성자 ID
- path | VARCHAR(25) | 경로(무한뎁스가 가능 하지만, 그냥 제한으로 5뎁스 까지만)
- deleted | BOOL | 삭제여부
- created_at | DATETIME | 생성시간

### article_like
- article_like_id | BIGINT | PK
- article_id | BIGINT | 게시글 ID(Shard Key)
- user_id | BIGINT | 사용자 ID
- created_at | DATETIME | 생성 시간

### article_like_count
- article_id | BIGINT | PK(Shard Key) => article_like 테이블과 동일한 샤드에서 트랜잭션 처리 위함
- like_count | BIGINT | 좋아요 수
- version | BIGINT | 낙관적 락 버전 컬럼

### board_article_count
- board_id | BIGINT | PK(Shard Key) => 게시글 서비스의 article 테이블과 동일한 샤드 키
- article_count | BIGINT | 게시글 수

### article_comment_count
- article_id | BIGINT | PK(Shard Key) => 댓글 서비스의 comment 테이블과 동일한 샤드 키
- comment_count | BIGINT | 댓글 수

### article_view_count
- article_id | BIGINT | PK(Shard Key)
- view_count | BIGINT | 조회수

### outbox
- outbox_id | BIGINT | PK(Shard Key)
- event_type | VARCHAR(100) | 이벤트 타입
- payload | VARCHAR(5000) | 이벤트 데이터
- shard_key | BIGINT | 샤드 키
- created_at | DATETIME | 생성일시

```
샤딩이 고려된 DB + 트랜잭션은 각 샤드에서 단일 트랜잭션으로 빠르고 안전하게 수행 + 여러 샤드간에 분산 트랜잭션을 지원하는 DB도 있으나, 성능이 다소 떨어짐
Outbox Table에 이벤트 데이터 기록과 비즈니스 로직에 의한 상태 변경이 동일한 샤드에서 단일 트랜잭션으로 처리될 수 있도록 함
```

## 1. 대규모 시스템 서버 인프라 기초

### 대규모 시스템 서버 인프라 기초
- 클라이언트 요청이 많아지만 서버는 Scale Up 또는 Scale Out 해야한다.
- 만약 Scale Out을 하는 경우 N개의 서버가 생기면서 클라이언트는 각각의 서버 정보를 알아야 한다. 하지만 앞단에 로드밸런서를 두면서 요청 정보를 분산하면서 동시에 클라이언트는 서버의 정보를 알 필요가 없다.
- 물론 Scale Out은 로드밸런서, 서버, 데이터베이스 모두 적용할 수 있다.
- 하지만 클라이언트에서 요청을 보내는 과정이 복잡하면 응답시간이 오래걸린다. 따라서 캐시를 이용할 수 있다.
- N개의 로드밸런서 앞단에 캐시를 두거나, N개의 서버 앞단에 캐시를 두거나, N개의 데이터베이스 앞단에 캐시를 두고 더 빠르게 데이터에 접근할 수 있다.
- 캐시 또한 Scale Out을 적용할 수 있다.
- 만약, 네트워크 문제 또는 자연 재해와 같은 문제로 인해서 로드밸런서, 서버, 데이터베이스 중 1개가 손실될 수 있기 때문에, 여러 장소에 둘 수 있다. 그 결과 거대한 데이터 센터가 만들어진다.
- 애플리케이션이 점차 커지면, 단일 서버의 트래픽이 올라간다. 따라서 리소스 부족, 유지보수 어려움 등의 문제가 생긴다. 따라서 단일 애플리케이션을 각 기능에 맞게 N개의 애플리케이션으로 분리한다. 예를 들면, 결제부분, 주문부문 등등.
- 분리된 애플리케이션이 하나의 서비스로 돌아가려면, 각 서버들은 네트워크 통신을 해야한다. 직접접인 API 통신과 간접접인 이벤트 기반 메시지 통신이 있다.

### 모놀리식
- 모놀리식은 1개의 애플리케이션에 N개의 서비스 기능이 있는 것이다. 따라서 특정 기능에 대한 트래픽이 상승하면 Scale Up을 할 수 있다. 하지만 한계가 있다. 가격이 비싸다. 따라서 Scale Out을 고려할 수 있다.
- 그러면 트래픽이 증가해서 Scale Out을 한 결과, N개의 동일한 서비스를 가진 애플리케이션이 N개가 생겼다. 리소스 낭비다. 왜냐하면 특정 서비스의 기능 부분만 트래픽이 생겼는데, 다른 서비스까지 애플리케이션 자체로 복제를 했기 때문이다.
- 그리고 1개의 애플리케이션은 N개의 서비스 기능이 있기 때문에 1개의 서비스가 에러가 발생하면 모든 애플리케이션은 사용할 수 없다.
- 또한 모놀리식은 빌드와 배포 시, 1개의 애플리케이션 전체를 빌드하고 배포해야 하기 때문에, 만약 내가 특정 기능만 변경 후 빌프와 배포를 하려해도 전체 애플리케이션을 전부 빌드와 배포를 해야하는 단점이 있다. 또한 크기가 커지면서 시간이 매우 오래 걸린다.

### MSA
- 모놀리식 단점을 해결하기 위해, 각 서비스들을 애플리케이션으로 만든다. 따라서 특정 애플리케이션이 에러가 발생해도 다른 애플리케이션에게 영향을 주지 않는다.
- 그리고 특정 애플리케이션만 빌드하고 배포하기 때문에 속도와 시간이 매우 빠르다.
- 또한 특정 서비스의 애플리케이션 트래픽이 증가하면 해당 애플리케이션만 Scale Out 하면 되기 때문에, 낭비되는 리소스가 없다.
- 따라서 확장성은 유연하다. 하지만 서비스(애플리케이션)간의 통신 및 모니터링과 데이터의 일관성을 위한 트랜젝션 관리의 어려움이 존재한다.

### 도커
- 도커 이미지는 실행파일이고, 컨테이너는 실행된 프로세스다. 즉, 이미지는 애플리케이션을 실행할 템플릿이다.
- docker images
- docker pull
- docker ps / docker ps -a
- docker run
- docker start
- docker stop
- docker exec
- docker rm
- docker rmi

---

## 2. 분산 관계형 데이터베이스

- article 서비스에는 CRUD가 있다고 가정하자. 서비스가 활성화가 되면서 단일DB는 부하를 견디지 못한다. 저장해야 할 트래픽이 많아지면 단일DB를 Scale Up을 할 수 있다. 하지만 부담이 커질수록 매번 Scale Up을 할 수 없다.
- DB를 1개 더 늘리는 Scale Out을 하자. 그러면 클라이언트 요청은 분산해서 DB로 들어간다. 샤딩(데이터를 여러DB에 분산하여 저장하는 기술)을 이용해서 데이터를 여러DB에 분산한다. 샤딩된 데이터를 샤드라고한다.

### 수직샤딩, 수평샤딩
- 수직샤딩: 데이터를 수직(컬럼)으로 분할한다. 즉, 왼쪽DB에는 id, article_id 오른쪽DB에는 board_id, created_at이 들어가는 것이다.
  - 장점: 각 샤드가 적은 수의 컬럼을 저장하기 때문에 공간의 이점이 생긴다.
  - 단점: 데이터 분리 때문에 조인 또는 트랜젝션의 어려움이 생긴다.
- 수평샤딩: 왼쪽DB에는 id 1 ~ 5000번까지 넣고, 오른쪽DB에는 5001 ~ 10000번까지 넣는 것이다.
  - 장점: 각 샤드에 데이터가 분산되기 때문에 공간의 이점이 생긴다.
  - 단점: 데이터 분리 때문에 조인 또는 트랜젝션의 어려움이 생긴다.
### 범위 기반 샤딩
- 데이터를 특정 값(샤드 키)의 특정 범위에 따라 분할하는 방법이다. 즉, 수평샤딩과 비슷하다.
- 장점: 만약 id 1 ~ 4000번까지 조회하는 경우 왼쪽DB에서 전부 가져올 수 있다.
- 단점: 데이터가 쏠려있기 때문에 1번 ~ 6000번까지 조회하는 경우 망한다. 
### 해시 기반 샤딩
- 데이터를 특정 값(샤드 키)의 해시함수를 이용해서 분할하는 방법이다. 즉, 골고루 분산한다는 것이다. 
- hash_function = article_id % 2 이런 방법으로 각 DB에 균등하게 넣는다.
- 단점: 목록조회와 같은 범위 데이터 조회에 좋지않다. 1번 ~ 4000번 조회하는 경우를 생각하자. 모든 샤드에 요청을 해야한다.
### 디렉토리 기반 샤딩
- 디렉토리를 사용해서 데이터가 저장된 샤드를 관리하는 방법이다.
- 디렉토리에 아이디와 샤드 키가 명시되어 있어, 데이터를 디렉토리에 맞게 관리한다.
- 디렉토리 관리비용이 존재한다.
### 물리적 샤딩, 논리적 샤딩
- 물리적 샤드
  - 물리적 샤드는 진짜로 DB를 더 생성하는 것이다. DB를 계속 생성하면 샤드 키 기반으로 데이터의 재배치가 필요하다.
  - 또한 클라이언트에서 새롭게 생성되는 샤드의 정보를 알아야한다. 왜냐하면 접근을 해야하기 때문이다.
  - 즉, DB를 확장하면 클라이언트가 DB정보를 알아야하는 불편함이 존재한다.
- 논리적 샤드
  - 1개의 샤드 내부에서 물리적 샤드처럼 분리한다고 가정하는 것이다. 즉 가상의 샤드를 만드는 것이다.
  - 물리적 샤드는 위치를 알 수 있지만, 논리적 샤드는 위치를 알 수 없기 때문에 어떤 물리적 샤드에 위치하는지 알아야 한다. 따라서 라우터가 필요하다.
  - 클라이언트와 DB내부의 샤드들 사이에 샤드 라우터를 만들고, 샤드 라우터 내부에 논리적 샤드와 물리적 샤드를 라우팅하게 만든다.
  - 클라이언트가 논리적 샤드 기반으로 샤드 라우터에 요청을 보내면, 샤드 라우터는 물리적 샤드로 라우팅을 한다.
  - 만약 물리적 샤드가 늘어나는 경우, 추가된 샤드 정보를 샤드 라우터에 등록하면 된다.
### Primary, Replica
- 장애가 발생할 경우 복제본을 사용한다.
- Primary는 주 DB이고, Replica는 복제본이다. Primary에서 Replica로 복제하는 경우는 2개의 방법이 있다.
- 동기적(Sync)으로 복제하면 데이터의 일관성을 유지할 수 있지만, 쓰기 성능이 저하된다.
- 비동기적(Async)으로 복제하면 쓰기 성능은 유지되나 복제본에 최신 데이터가 반영되지 않는다.

### 왜 article_id 대신 board_id가 샤드일까 
- 게시글(article)은 게시판 단위로 서비스를 이용, 따라서 게시판(board_id)단위로 게시글(article) 목록이 조회된다.
- 만약 샤드 키가 article_id면, 1번 board -> (1번 article 왼쪽 샤드) (2번 article 오른쪽 샤드), 따라서 board의 article 목록 조회하는 경우에는 모든 샤드를 조회해서 가져와야 하는 문제가 생긴다.
- 따라서 board_id를 샤드 키로 선정한다. 1번 board -> (1, 2 article 왼쪽 샤드)

### Primary Key - SnowFlake
- 분산 시스템에서 고유한 64비트 ID를 생성하는 알고리즘
- [1비트][41비트:TimeStamp][10비트:노드 ID][12비트:Sequence번호]
- 분산 환경에서도 중복 없이(노드 ID + Sequence 번호) 순차적(TimeStamp)으로 ID 생성

### 대규모 데이터에서의 게시글 목록 조회
- 대규모 데이터에서 목록 조회는 복잡하다. 왜냐하면 모든 데이터를 전부 보여줄 수 없다. 따라서 페이징이 필요하다.
- 모든 데이터를 가져오고 특정 페이지만 추출하는 것은 비효율적이다. 또한 이 경우는 메모리에 모든 데이터가 갈 수 없기 때문에 디스크에서 가져오고 시간이 오래걸린다. 그리고 메모리 용량을 초과할 수 있다.
- 따라서 특정 페이지의 데이터만 바로 추출하는 방법이 필요하다. 그 방법이 페이징 쿼리이다. 방법은 페이지 번호 방식과 무한 스크롤 방식으로 나뉜다.
- 기본 페이징
  - N번 페이지에서 M개의 게시글
  - 게시글의 개수
  - 샤드 키는 board_id이기 때문에 단일 샤드에서 게시글 목록 조회가 가능하다.
  - limit = M개의 게시글
  - offset = (N번 페이지 - 1) * M
  - ```sql
    select * from article
      where board_id = {board_id} // 게시판 별
      order by created_at desc // 최신순
      limit {limit} offset {offset} // N번 페이지에서 M개
    
    // 1번 게시판, 4번 페이지에서 30건의 데이터 조회
    select * from article
      where board_id = 1
      order by created_at desc
      limit 30 offset 90;
    ```
  - `쿼리 시간: 5.70 sec`
  - explain => type: ALL(풀 스캔), extra: Using where(where 조건 필터링); Using filesort(데이터가 많아 디스크에서 데이터를 정렬) => 따라서 인덱스를 사용해야 한다.

### 인덱스
- 관계형 데이터베이스에서는 주로 B+트리로 구성되어 있다. 즉, 데이터가 정렬된 상태로 저장되고, 검색 삽입 삭제 연산이 log 시간에 수행된다. 따라서 트리 구조에서 leaf node 간 연결되기 때문에 범위 검색 효율적이다.
- 따라서 인덱스를 추가하면, 쓰기 시점에 B+트리 구조의 정렬된 상태의 데이터가 생성된다. 
- 이미 인덱스로 지정된 컬럼에 대해 정렬된 상태를 가지고 있기 때문에, 조회 시 전체 데이터를 정렬하고 필터링할 필요가 없다. => 조회쿼리 빠름
- ```sql
  create index idx_board_id_article_id on article(board_id asc, article_id desc);
  ```
- 왜 인덱스에 생성시간이 아닌, article_id가 사용? 왜냐하면 게시글 서비스는 여러 서버로 분산되어 동시에 처리가 가능하다. 따라서 게시글이 동시에 생성될 수 있기 때문이다. 따라서 created_at을 정렬조건으로 하면 순서가 명확하지 않을 수 있다.
- 1200만건 데이터를 넣을 때 멀티스레드로 작업을 했기 때문에 동시에 생성된 데이터가 많을 것이다.
- 그래서 고유한 오름차순을 위해 snow flake가 적용된 article_id를 사용하는 것이다.
- ```sql
  select * from article
    where board_id = {board_id}
    order by article_id desc
    limit {limit} offset {offset};
  ```
- `쿼리 시간: 0.01 sec`
- explain => key: idx_board_id_article_id => 생성된 인덱스가 쿼리에 사용
- 이번에는 50,000 페이지를 조 => limit 30 offset 1499970
- `쿼리 시간: 3.46 sec`
- explain => key: idx_board_id_article_id => 생성된 인덱스가 쿼리에 사용, 쿼리에서 변경된 것은 offset

### 인덱스의 종류
- 클러스터 인덱스(Primary Key), 세컨더리 인덱스를 알아보자. 먼저 MySQL의 기본 스토리지 엔진(DB에서 데이터 저장 및 관리 장치)은 InnoDB 다.
- 그리고 InnoDB는 테이블마다 클러스터 인덱스를 자동 생성한다. Primary Index
- 클러스터드 인덱스는 leaf node에 행 데이터(진짜 데이터)를 가지고 있다. 즉, Primary Key를 이용한 조회는 클러스터 인덱스로 조회를 하고 있는 것이다.
- 우리가 생성한 인덱스는 세컨더리 인덱스다. 세컨더리 인덱스는 인덱스 컬럼 데이터와 데이터에 접근하기 위한 포인터(클러스터 인덱스를 가리키는 포인터, Primary key)를 가지고 있다.
- 따라서 세컨더리 인덱스를 이용환 조회는 세컨더리 인덱스를 통해 Primary Key를 찾고, 클러스터 인덱스(Primary Key)를 사용해서 실제 데이터를 찾는 것이다. 따라서 인덱스 트리를 2번 탄다.
- ```sql
  select * from article
    where board_id = 1
    order by article_id desc
    limit 30 offset 1499970;
  ```
- 위의 쿼리를 보면 30개의 데이터만 필요하지만 offset 0 부터 모든 데이터에 접근하는 단점이 있다. 
- 세컨더리 인덱스는 board_id, article_id를 포함한다. 따라서 세컨더리 인덱스에서 필요한 30건에 대해서 article_id만 먼저 추출한다. 그리고 해당 30건만 클러스터 인덱스에 접근하면 된다.
- ```sql
  select board_id, article_id from article
    where board_id = 1
    order by article_id desc
    limit 30 offset 1499970;
  ```
- `쿼리 시간: 0.36 sec`
- explain => key: idx_board_id_article_id, extra: Using index(인덱스의 데이터만으로 조회를 수행할 수 있는 인덱스, 커버링 인덱스)
- 커버링 인덱스는 클러스터 인덱스를 읽지 않고, 세컨더리 인덱스만 사용 가능한 인덱스
- ```sql
  select * from (
    select article_id from article
        where board_id = 1
        order by article_id desc
        limit 30 offset 1499970
  ) t left join article on t.article_id = article.article_id;
  ```
- `쿼리 시간: 0.28 sec`
- explain => article_id 추출을 위한 서브쿼리에서 파생테이블(DERIVED) 생성, 커버링 인덱스

### 게시글 목록 조회 - 페이지 번호 방식
- ```sql
  select count(*) from article where board_id = 1;
  ```
- `쿼리 시간: 1.97 sec`
- explain => 커버링 인덱스로 동작
- 공식 => (((n - 1) / k) + 1) * m * k + 1
- ```sql
  select count(*) from (select article_id from article where board_id = {board_id} limit {limit}) t;
  ```
- `쿼리 시간: 0.09 sec`
- explain => 커버링 인덱스로 동작
- count 쿼리에서는 limit은 동작하지 않고 전체 개수를 반환한다. 따라서 서브 쿼리에서 커버링 인덱스 limit만큼 조회하고 count하는 방식

### 게시글 목록 조회 - 무한 스크롤
- 무한 스크롤은 기준점이 중요하다. 따라서 기준점을 인덱스에서 로그 시간에 즉시 찾을 수 있기 때문에, 이무리 뒷 페이지를 가더라도 균등한 조회 속도가 보장된다.
- ```sql
  -- 1번 페이지(기준점 없음)
  select * from article where board_id = {board_id} order by article_id desc limit 30;
  
  -- 2번 페이지 이상(기준점 = {last_article_id})
  select * from article
  where board_id = {board_id} and article_id < {last_article_id}
  order by article_id desc limit 30;
  ```
- `쿼리 시간: 0.00 sec`
- `쿼리 시간: 0.00 sec`

### Primary key 생성 전략
- DB auto increment
  - 단점: 분산DB 환경에서 PK가 중복될 수 있기 때문에, 식별자의 유일성이 보장되지 않는다. 여러 샤드에서 동일한 PK를 가지는 상황이 발생할 수 있다. 또한 클라이언트 측에 노출되는 보안의 단점이 있다. 따라서 보안 측면이 걱정인 경우, PK는 DB 식별자로 사용하고 애플리케이션 식별자를 위해 별도의 유니크 인덱스를 사용할 수 있다.
  - 장점: 간단하다
- 유니크 문자열 또는 숫자
  - UUID 또는 난수를 생성해서 PK를 지정한다. 즉 랜덤 방식이다. 따라서 랜덤 방식이기 때문에 성능 저하가 발생한다.
  - 성능 저하가 발생하는 이유는 클러스터 인덱스는 정렬 상태를 유지하기 때문이다. 즉, 데이터 삽입이 필요한 인덱스 페이지가 전부 찬 경우, B+트리 재구성과 페이지 분할로 IO가 증가한다.
  - 또한 PK를 이용한 범위 조회가 필요하다며느 디스크애서 랜덤 IO가 발생하기 때문에, 순차 IO보다 성능이 저하된다.
- 유니크 정렬 문자열
  - 분산 환경에 대한 PK 중복 문제를 해결할 수 있다. 그리고 랜덤 데이터를 생성에 의한 성능 문제를 해결한다.
  - 데이터 크기에 따라 공간과 성능 효율이 달라진다. => PK가 크면 클수록 데이터는 더 많은 공간을 차지하고 많은 비용을 소모한다.
- 유니크 정렬 숫자
  - 분산 환경에 대한 PK 중복 문제를 해결할 수 있다. 그리고 랜덤 데이터를 생성에 의한 성능 문제를 해결한다.
  - Snowflake => 문자열 방식보다는 적은 공간을 사용한다.

## 3. 댓글
- 계층형 구조의 댓글 목록 조회, 일반적으로 오래된 댓글이 먼저 노출된다. 또한 단순히 댓글의 생성 시간(comment_id)로는 정렬 순서가 명확하지 않다. 왜냐하면 계층 관계는 더 늦게 작성된 댓글이 먼저 노출될 수 있기 때문이다.
- 즉, 대댓글이 먼저 위로 올라갈 수 있다. 따라서 상위 댓글은 하위 댓글보다 먼저 생성되고, 하위 댓글은 상위 댓글 별 순서로 생성된다.
- ```sql
  -- index
  create index idx_article_id_parent_comment_id_comment_id on comment (
    article_id asc,
    parent_comment_id asc,
    comment_id asc
  );
  ```
- N번 페이지에서 M개의 댓글 조회
- ```sql
  select * from (
    select comment_id from comment
        where article_id = {article_id}
        order by parent_comment_id asc, comment_id asc
        limit {limit} offset {offset}
  ) t left join comment on t.comment_id = comment.comment_id;
  ```
- 댓글 개수 조회
- ```sql
  -- 커버링 인덱스로 동작
  select count(*) from (
    select comment_id from comment where article_id = {article_id} limit {limit}
  ) t;
  ```
- 무한 스크롤 1번페이지
- ```sql
  select * from comment
    where article_id = {article_id}
    order by parent_comment_id asc, comment_id asc
    limit {limit};
  ```
- 무한 스크롤 2번 페이지 이상, 기준점 2개
- ```sql
  select * from comment
    where article_id = {article_id} and (
        parent_comment_id > {last_parent_comment_id} or
        (parent_comment_id = {last_parent_comment_id} and comment_id > {last_comment_id})
    ) 
    order by parent_comment_id asc, comment_id asc
    limit {limit};
  ```
  
### 댓글 목록 조회 - 무한뎁스
- 최대 2뎁스와 동일한 방식으로 정렬 순서를 나타내보자. 먼저 계층별 오래된 순서를 나타낼 수 있어야 한다.
- 상위 댓글은 항상 하위 댓글보다 먼저 생성되고, 하위 댓글은 상위 댓글 별 순서대로 생성된다. 무한 뎁스에서는 상하위 댓글이 재귀적으로 무한할 수 있기 때문에, 정렬 순을 나타내기 위해 모든 상위 댓글의 정보가 필요하다.
- parent_comment_id = 1 > 2 > 6 (표시방법) => 인덱스가 필요하다. 하지만 가변적이고 데이터 형태가 복잡하다. => 뎁스의 순서를 문자열료 표시 => 경로 열거 방식
- xxxxx(1뎁스) | xxxxx(2뎁스) | xxxxx(3뎁스) | ....(4뎁스) => N 뎁스는 (n * 5) => 문자열로 모든 상위 댓글에서 각 댓글까지의 경로를 저장하는 방식, 따라서 각 경로를 상속하면 독립적
- 00000 => 00000 00000, 00000 00001 => ... 00000 00001 00000, 이 경우 문자열이기 때문에 지금은 0 ~ 9 10^5 100000개의 가능성이 존재하지만, 0 ~ 9(10개) + A ~ Z(26개) + a ~ z(26개) = 62개의 문자 사용이 가능하다. 문자의 순서는 0~9 < A~Z < a~z. 따라서 62^5만큼 가능하다.
- 따라서 문자열에 순서를 우리가 정의해야 한다. 그래서 DB에서 collation을 변경해야 한다. 디폴트는 utf8mb4_0900_ai_ci이다. 
  - utf8mb4는 각 문자 최대 4바이트 utf8 지원, 0900은 정렬 방식 버전, ai는 악센트 비구분, ci는 대소문자 비구분 => 우리는 (0-9, A-Z, a-z)사용하기 때문에 대소문자 순서를 구분해야 한다.
  - utf8mb4_bin으로 대소문자의 순서를 구분하게 만들 것
- ```sql
  create table comment_v2 (
    comment_id bigint not null primary key,
    content varchar(3000) not null,
    article_id bigint not null,
    writer_id bigint not null,
    path varchar(25) character set utf8mb4 collate utf8mb4_bin not null,
    deleted bool not null,
    created_at datetime not null
  );
  
  create unique index idx_article_id_path on comment_v2 (
    article_id asc, path asc
  );
  
  -- 확인
  select table_name, column_name, collation_name from information_schema.COLUMNS
  where table_schema = 'comment' and table_name = 'comment_v2' and column_name = 'path';
  ```
- descendantsTopPath 구하는 쿼리
- ```sql
  select path from comment_v2
    where article_id = {article_id}
        and path > {parentPath} -- parent 본인은 미포함 검색 조건
        and path like {parentPath}% -- parentPath를 prefix로 하는 모든 자손 검색 조건
  order by path desc limit 1; -- 조회 결과에서 가장 큰 path
  ```
- 페이지 번호, 목록 조회
- ```sql
  select * from (
    select comment_id
    from comment_v2
    where article_id = {article_id}
    order by path asc
    limit {limit} offset {offset}
  ) t left join comment_v2 on t.comment_id = comment_v2.comment_id;
  ```
- 페이지 번호, 카운트 조회
- ```sql
  select count(*) from (
    select comment_id from comment_v2 where article_id = {article_id} limit {limit}
  ) t;
  ```
- 무한 스크롤, 1번 페이지
- ```sql
  select * from comment_v2
    where article_id = {article_id}
    order by path asc limit {limit};
  ```
- 무한 스크롤, 2번 페이지 이상 => 기준점은 last_path
- ```sql
  select * from comment_v2
    where article_id = {article_id} and path > {last_path}
    order by path asc
    limit {limit};
  ```
  
## 4. 좋아요
- ```sql
  create table article_like (
    article_like_id bigint not null primary key,
    article_id bigint not null,
    user_id bigint not null,
    created_at datetime not null
  );
  
  create unique index idx_article_id_user_id on article_like(article_id asc, user_id asc);
  ```
  
### 좋아요 수
- 좋아요는 실시간으로 빠르게 전체 개수를 보여줘야 한다. 조회 시점에 전체 개수를 조회하는게 큰 비용이 들 수 있다.
- 좋아요 테이블의 게시글 별 데이터 개수를 미리 하나의 데이터로 비정규화한다.
- 좋아요 수는 쓰기 트래픽이 적다. 즉, 사용자는 게시글을 조회하고 좋아요를 수행한다. 따라서 데이터의 일관성이 중요하다.
- 쓰기 트래픽이 비교적 크지 않고 일관성이 중요하다면, 관계형 DB의 트랜젝션을 활용한다. 좋아요 테이블의 데이터 생성/삭제와 좋아요 수 갱신을 하나의 트랜젝션으로 묶는 것이다.
- 게시글 테이블 컬럼에 좋아요 수가 있다고 가정하자. 게시글 테이블에 좋아요 수 컬럼을 추가하고 좋아요가 생성/삭제 시 숫자를 갱신한다.

### 레코드 락
- 레코드에 락을 거는 것, 동일한 레코드를 동시에 조회 또는 수정할 때 데이터의 무결성 보장, 경쟁 상태 방지를 하는 것이다.
- 락이 걸리면 => LOCK_TYPE = RECORD, LOCK_MODE = X(Exclusive Lock)
- 트랜젝션을 시작하고 커밋을 하기 전까지 점유를 하고 그 중간에 다른 곳에서 업데이트를 하는 경우, 이미 다른 곳에서 점유하고 있어 해당 DB가 커밋을 해야 다른 곳의 업데이트가 반영이 된다.
- 레코드 락으로 게시글 테이블에 좋아요 수 컬럼을 비정규화하는 것은 제약이 있다. => 게시글은 작성한 사용자가 쓰기 작업을 하고 트래픽이 적다. 하지만 좋아요 수는 조회한 사용자가 쓰기 작업을 하고 트래픽이 많다.
- 따라서 서로 다른 주체에 의해 레코드 락이 잡힐 수 있기 때문이다. => 게시글 쓰기와 좋아요 수 쓰기는 사용자 입장에서는 독립적이다.

### 분산 트랜젝션
- 현재 우리 DB는 분산 DB이기 때문에, 분산환경은 트랜젝션 관리가 어렵다. 따라서 좋아요 서비스가 있는데, 게시글 서비스에서 관리할 필요가 없다. 따라서 좋아요 서비스 DB에서 좋아요 수를 관리한다.
- 좋아요 생성/삭제 좋아요 수 갱신은 하나의 트랜젝션으로 묶어서 처리한다.

### 동시성 문제
- 트랜잭션을 사용하면, 동시성 문제로 인해 구현 방법에 따라 데이터의 일관성은 깨질 수 있다. 왜냐하면 커밋을 하기 전에는 반영이 되지 않기 때문이다.
- 따라서 Lock을 사용한다. 락을 사용해서 동시성 문제와 모든 요청을 누락 없이 처리할 수 있다. 레코드 락을 사용하자.
- 만약 레코드 락을 사용하면, 특정 트랜젝션이 점유하고 커밋을 해야 다른 트랜잭션이 커밋되는 처리 지연 문제가 발생한다. 따라서 리소스를 점유하고 있는 블로킹 작업은 장애가 발생할 여지가 있다. => WAIT
- 트래픽이 많으면 동시성 문제는 불가피하다. 왜냐하면 여러 개의 요청이 1개의 좋아요 수 레코드를 수정하기 때문이다. => 비관적 락, 낙관적 락, 비동기 순차 처리

### 비관적 락
- 데이터 접근 시 항상 충돌한다라고 가정하는 락이다.
- 데이터를 보호하기 위해 항상 락을 걸어 다른 트랜잭션 접근을 방지한다. 따라서 다른 트랜잭션은 락이 해제되기까지 대기하고, 락을 오래 점유하고 있으면, 성능 저하 또는 데드락의 가능성이 올라간다. => record lock(비관적 락)
- ```sql
  -- 방법 1
  transaction start;
  insert into article_like values({article_like_id}, {article_id}, {user_id}, {created_at]});
  update article_like_count set like_count = like_count + 1 where article_id = {article_id}; -- 좋아요 수 데이터 갱신, 비관적 락 점유
  commit; -- 비관적 락 해제
  -- 락 점유 시점이 짧다.
  -- DB의 현재 저장된 데이터를 기준으로 처리하기 때문에 SQL문을 직접 전송한다.
  
  -- 방법 2
  transaction start;
  insert into article_like values({article_like_id}, {article_id}, {user_id}, {created_at]});
  select * from article_like_count where article_id = {article_id} for update; -- 조회된 데이터에 대해서 비관적 락 점유(이 시점 부터 다른 Lock은 점유 불가능), 애플리케이션단에서 JPA는 객체로 조회할 수 있다.
  update article_like_count set like_count = {updated_like_count} where article_id = {article_id}; -- 좋아요 수 데이터 갱신, 조회된 데이터를 기반으로 새로운 좋아요 수를 만든다.(조회 시점부터 락을 점유하고 있어서), 애플리케이션에서 JPA를 사용하면, 엔티티로 수행 가능
  commit; -- 비관적 락 해제
  -- 락 점유 시점이 길다. 데이터를 조회한 뒤 중간 과정을 수행하기 때문에 락 해제가 지연된다.
  -- JPA를 사용하는 경우, 엔티티를 이용하여 조금 더 객체지향으로 개발한다.
  ```
  
### 낙관적 락
- 데이터 접근 시 항상 충돌은 발생하지 않는다라고 가정한다.
- version 컬럼을 사용해서 데이터의 변경 여부를 확인한다. 따라서 락을 명시적으로 잡는 과정이 없다.
- 낙관적 락은 충돌을 감지하고 후처리를 위한 추가 작업이 필요하다. 즉 2개의 요청이 들어오면, 1번째 요청이 데이터를 변경 시 version 컬럼 +1을 추가한다. 2번째 요청이 데이터를 변경하기 위해 version = 1을 하는 순간 이미 version은 2로 변경이 되어있기 떄문에 누군가 작업을 진행 중이라는 것을 알 수 있다. 
- 충돌 시, 롤백처리는 버전관리를 위해 @Version 을 사용하면 알아서 처리한다.

### 비동기 순차 처리
- 모든 상황을실시간으로 처리하고 즉시 응답해줄 필요는 없다는 관점이다.
  - 요청을 대기열에 저장하고, 이후에 비동기로 순차적으로 처리할 수 있다. 따라서 게시글마다 1개의 스레드에서 순차적으로 처리하면, 동시성 문제도 사라진다.
  - 락으로 인한 지연이나 실패 케이스가 최소화된다. 즉시 처리되지 않기 때문에 사용자 입장에서는 지연될 수 있다.
- 비싸다.
  - 비동기 처리를 위한 시스템 구축 비용 + 데이터 일관서 관리를 위한 비용(대기역에서 중복/누락 없이 반드시 1회 실행 보장되기 위한 시스템 구축이 필요하다.)
  - 실시간으로 결과 응답이 안되기 때문에 클라이언트 측 추가 처리 필요하다.(이미 처리된 것처럼 보이게 또는 실패 시 알림주기)

### 게시글 수, 댓글 수
- 게시글 수와 댓글 수도 좋아요 수와 동일한 방법으로 만들 수 있다. => 비관적 락 1번

## 5. 조회 수
- 조회 수는 게시글이 조회된 횟수만 저장한다. => 사용자는 내역을 확인하지 못하기 때문에, 조회수만 보여주면 끝
  - 즉, 전체 조회수를 단일 레코드에 비정규화 상태로 바로 저장해도 충분하다.
- ```sql
  start transaction;
  -- 게시글/댓글/좋아요 데이터 삽입, 조회수는 이 과정만 제거하면 될 것 같다.
  -- 게시글/댓글/좋아요 수 데이터 갱신 
  commit;
  ```
- 일단 충분한지 비교를 하자. 게시글/댓글/좋아요 수의 데이터 특성 비교
  - 데이터 일관성 => 게시글/댓글/좋아요 수는 일관성이 중요하다. 하지만 조회수는 비교적 일관성이 덜 중요하다.
    - 게시글/댓글/좋아요 수는 트랜젝션을 지원하는 RDB를 사용했다. 트랜잭션을 사용하여 데이터의 일관성을 지키고, 안전한 저장소에 영구정으로 저장한다. 하지만 디스크 접근 비용과 트랜잭션 비용이 발생한다.
  - 쓰기 트래픽 => 게시글/댓글/좋아요 수는 비교적 트래픽이 적지만, 조회수는 트래픽이 많다.
- 조회수 => 데이터의 일관성이 덜 중요하고, 다른 데이터에 의해 파생되는 데이터가 아니다. 또한 트랜잭션이나 안전한 저장소가 반드시 필요하지 않다. 그리고 트래픽이 많기 떄문에 In-memory Database 사용할 것이다. 인메모리 DB는 데이터를 메모리에 저장 및 관리하는 DB다.
- 왜냐하면 디스크에 접근하는 것 보다 메모리 접근이 훨씬 빠르기 때문이다.

### redis
- 인 메모리 DB + 고성능 + NoSQL(관계형 DB처럼 정해진 스키마가 없어서 유연) + key/value 저장소 + 다양한 자료구조(String, List, Set, Sorted Set, Hash)
- TTL(Time to Live) 지원 => 일정 시간이 지나면 데이터 자동 삭제
- 싱글스레드 => 순차적이기 때문에 동시성 문제를 해결할 수 있다.
- 데이터 백업 지원 => 메모리는 휘발성 저장소지만, Redis에서 데이터를 안전한 디스크에 저장ㅈ하는 방법도 제공(AOF, RDB)
- Redis Cluster => 확장성, 부하 분산, 고가용성을 위한 분산 시스템 구성 방법 제공
- 따라서 사용 사례는 아래와 같다.
  - 고성능 작업 => 메모리는 디스크보다 상대적으로 빠르기 때문에, Redis 를 DB로 사용할 수 있다. 또한 싱글 스레드로 동작해서 동시성 문제에 유리하다.
  - 캐시 => Redis는 캐시로 사용될 수 있다. 즉, 빠르게 데이터를 가지고 올 수 있다. 매번 디스크에서 데이터를 접근하면 느리므로, Redis에 데이터를 일정 시간 캐시할 수 있다.
  - pub/sub => Redis는 메시지를 발행 및 구독할 수 있기 때문에 실시간 통신에도 활용할 수 있다.
- ```
  docker run --name kuke-board-redis -d -p 6379:6379 redis:7.4
  
  -- redis는 NoSQL이기 때문에 DB에서 테이블 스키마 생성 X, 그냥 바로 K,V로 지정하면 끝
  ```

### Distributed in-memory database - redis cluster
- 여러 개의 redis 서버가 클러스터를 이루면서 분산 시스템을 이룬다. => 확장성, 부하분산, 고가용성, 안정성 등을 지원하기 위한 redis cluster
- 따라서 redis cluster는 redis를 수평적으로 확장할 수 있기 만드는 기능이다. 그래서 샤딩을 지원한다. 특히 확장성을 위해서 논리적 샤드를 지원한다. (16384개의 Slot)
  - shard 선택 방식 => key의 hash 값으로 slot(논리적 샤드)를 구하고, slot으로 shard(물리적 샤드) 선택
    - slot = hash_function(key), shard = select_shard(slot)
- 서버가 추가되면, 자동으로 데이터가 분산된다. 또한 데이터 복제 기능을 제공한다.(고가용성)
- Redis Cluster는 샤딩을 지원하고, 16384개의 논리적 샤드(slot)로 분리된다. 이러한 슬롯은 각 물리적 샤드에 균등하게 분산될 수 있다. => 확장성, 부하 분산
- Redis Cluster는 데이터 복제도 지원한다. Primary(Shard)..Replica(Shard) 따라서 장애 시에도 유연하게 대처할 수 있는 고가용성을 제공한다.
- 조회수 데이터가 일관성이 덜 중요하지만, 어느정도의 영속성은 필요하다. => 레디스의 AOF, RDB로 데이터 영속성 관리를 하자.
  - AOF => Append Only File, 수행된 명령어를 로그 파일에 기록하고, 데이터 복구를 위해 로그를 재실행
  - RDB => Snapshot, 저장된 데이터를 주기적으로 파일에 저장 

### 백업 시스템
- redis에 저장된 데이터를 MySQL에 백업하자.
- 약간의 데이터 유실은 허용한다는 관점에서 실시간으로 모든 데이터를 백업할 필요는 없다.
- 시간 단위 백업 => N분 단위로 redis의 데이터를 MySQL로 백업 + 배치 또는 스케줄링 시스템 구축 + 백업 전 장애 시 유실 가능성 존재
- 개수 단위 백업 => N개 단위로 redis의 데이터를 MySQL로 백업 + 조회 시점에 간단히 처리 가능 + 백업 전 장애 시 유실 가능성 존재 + 개수 단위 안 채워지면 유싱 가능성 존재
- ```sql
  create table article_view_count (
    article_id bigint not null primary key,
    view_count bigint not null
  );
  ```
  
### 어뷰징 방지 정책
- 로그인 사용자에 대해서만 식별 => userId 별 식별
- 각 사용자는 게시글 1개당 1분에 1번 조회수 증가
- 그러면 각 사용자가 최근 10분 내에 게시글을 조회 했었다는 사실을 어떻게 알 수 있을까? => 스프링부트는 무상태 애플리케이션이기 때문에, 상태관리를 해야한다.
- MySQL 고려 => 조회수 증가 요청이 오면, 마지막 조회 시점을 조회하고 10분 이내의 조회 내역이 있는지 확인한다. 조회 내역에 따라서, 조회 내역이 없으면 조회수를 증가하고 현재 시간으로 마지막 조회 시점을 업데이트 한다. 반대로 조회 내역이 있으면 조회수를 증가하지 않는다.
- 하지만 게시글 조회 트래픽은 많다. 따라서 MySQL 대신 Redis를 사용할 것이다.
- 동시성 문제가 발생할 수 있다. 만약 조회수 동시 요청이 들어온다면, MySQL에서는 락을 점유하는 상황이 필요하다. 따라서 Redis는 싱글 스레드로 동작해서 하나의 명령어는 원자적으로 처리된다.(동시성에 유리)
- MySQL은 데이터 자동 삭제를 지원하지 않는다. => 게시글이 삭제되거나 더 이상 갱신될 일이 없다면, 직접 삭제를 위한 배치 등의 시스템을 구축하지 않는 이상 데이터는 영구히 남는다.
- Redis는 TTL(Time To Live) 지원 -> 별도 삭제 시스템을 구축하지 않아도, 데이터는 자동으로 삭제될 수 있다.
- 따라서 조회수 증가 요청이 오면, Redis TTL은 10분으로 데이터를 저장한다.
  - 게시글 조회는 사용자 단위로 식별되기 때문에 key는 articleId + userId가 된다.
  - 이미 저장된 데이터가 있으면 저장에 실패하는 명령어를 사용한다. => setIfAbsent(데이터가 없을 때에만 저장) ... True(성공) False(실패)
- 데이터 저장 성공 여부에 따라
  - 성공 시, 조회 내역이 없었음을 의미 -> 조회수 증가
  - 실패 시, 조회 내역이 있음을 의미 -> 조회수 증가 X
- 이런 과정은 사용자의 게시글 조회수 증가에 대해서 Lock을 획득하는 것이다. 현재 시스템은 확장성이 고려된 분산 시스템이고, 분산 시스템에서 락을 획득하는 것을 분산 락이라고 한다.
  - 사용자의 게시글 조회수 증가에 대해서 10분 간 분산 락을 획득, 분산 락이 점유되면, 다른 요청은 락이 10분 후 해제되기 까지, 락을 추가로 점유할 수 없다.
- 시나리오 => 동일한 사용자 중 1번째 요청으로 조회수 증가 요청이 오면, 이전에 점유되지 않았기 때문에 setIfAbsent=True(분산 락 획득), 후에 동일 게시글에 동일 사용자가 분산 락을 요청하면 setIfAbsent=False가 나온다.
- Redis는 싱글 스레드로 동작하고, setIfAbsent는 원자적을 처리되기 때문에 동시성 고려는 필요가 없다.

## 6. 인기글

### kafka
- 분산 이벤트 스트리밍 플랫폼이면서 대규모 데이터를 실시간으로 처리하기 위해 사용한다. 따라서 고성능, 확장성, 내구성, 가용성이 필요하다.
- 먼저 Producer 는 데이털를 생산하는 사람이고, Consumer 는 데이터를 소비하는 사람이다. 따라서 Producer 가 Consumer 에게 데이터를 전송하는 것이 주 목적이다. 직접적인 방법은 API 통신을 하는 것이다. 이 경우는 Producer와 Consumer가 직접적인 연결이 된다. 만약 이 구조에서 Consumer에서 장애가 발생 하면 데이터가 유실될 수 있다.
- 따라서 간접적인 방법이 필요하다. 그래서 Producer와 Consumer 사이에 Message Queue에 데이터를 전송을 대신하게 만들 수 있다. 이 경우는 장애 전파될 위험은 감소하고 데이터 유실 위험성도 낮아진다. 또한 직접적인 동기처리가 아닌 비동기로 처리가 된다.
- 만약 Producer, Consumer가 많아지면, 처리할 데이터가 늘어나고 Message Queue에서 장애가 발생할 확률도 올라간다. 또한 데이터에 대해 복잡한 라우팅이 생긴다. 그래서 단일 Message Queue로는 대규모 데이터를 처리하기 힘들다. 따라서 여러 Message Queue를 만들고 Message Broker를 만들어 Message Broker에서 Message Queue를 관리하도록 만들다. 이 경우는 대규모 데이터를 병렬로 처리하고 복잡한 데이터 요구사항을 처리한다.
- 그래도 만약 Producer의 생산량이 Consumer소비량보다 많거나 Broker의 처리량을 많으면, 안전할까? 리소스 부족으로 장애가 전파된다. 따라서 Consumer가 Broker로부터 데이터를 받는 것이 아니라. Pull 하도록 만들면 된다. 즉 Consumer는 자기의 처리량에 따라서 조절하는 것이다.
- 그래서 Producer가 Publish(생산)을 하면 Consumer는 구독(Subscribe)해서 가져오는 것이다. pub/sub 패턴이 만들어진다.
- 따라서 Kafka Broker는 데이터를 중개 및 처리해주는 애플리케이션 실행 단위를 제공한다. 그래서 Producer는 Broker에 데이터를 생산하고, Consumer는 Broker에서 데이터를 소비한다. 그래서 Producer와 Consumer는 다양한 형태의 데이터를 처리해야 한다. 그러면 Kafka는 어떻게 데이터들을 구분할까?
- Kafka는 데이터를 구분하기 위해서 topic이라는 단위를 사용한다. 즉, Producer는 topic 단위로 이벤트를 생산 및 전송하고, Consumer는 topic 단위로 이벤트를 구독 및 취소한다.
- 만약 처리하는 데이터가 많아지면 1대의 Broker가 모든 것을 처리할 수 있을까? 처리량을 늘릴 수 있는 방법은? => 여러 대의 Kafka Broker를 연결해서 Cluster를 이루게 하고, 처리량을 늘릴 수 있다. topic은 데이터를 구분하는 논리적 구분 단위이기 때문에, 여러 Broker에서 병렬 처리 함으로써 처리량을 늘릴 수 있다. 그러면 어떻게 topic들이 여러 Broker로 분산되는 것일까?
- 각 topic은 partition 단위로 물리적으로 분산될 수 있다. 따라서 Producer는 1개의 topic의 n개의 partition으로 분산하여 데이터를 처리하고, Consumer는 topic의 partition 단위로 데이터를 처리하는 것이다. 만약 3대의 Broker가 있는 경우, 각 topic의 모든 partition을 1대의 Broker에서 처리할 필요가 없다. 따라서 Kafka에서 각 topic의 partition은 여러 Broker에 균등하게 분산될 수 있다. 물리적으로 분산된 여러 개의 partition에 대해 각 Broker가 분산 처리할 수 있는 것이다. 따라서 Consumer들은 partition 단위로 구독하여 데이터를 처리한다.
  - EX) (topic1, partition1), (topic1, partition3) => consumer2
- partition 단위로 분산된 상황에서 순서를 고려한 처리는? => partition 단위로 데이터가 처리되기 떄문에, Producer는 topic에 생산되는 이벤트에 대해 직접 partition을 지정할 수 있고, partition을 지정하지 않는다면 RR방식으로 적절히 분산할 수 있다. 즉, 순서 보장이 필요한 이벤트들에 대해서는 동일한 partition으로 보내준다.
- 만약 특정 Broker에 장애가 발생하는 경우는? => 이 경우는 해당 Broker에 적용되어 있는 데이터는 유실된다. 따라서 복제를 해야한다. `replication factor = 3` 설정을 하면, 각 partition의 데이터는 3개로 복제된다. leader에 데이터를 쓰면, follower로 데이터가 복제된다. 각 복제본은 Kafka에서 여러 Broker간에 균등하게 분산해준다.
  - EX) 만약 Broker 2에 장애가 발생해도, Broker 1과 3에 follower(복제본)이 존재해서 데이터 생산 및 소비를 계속 처리할 수 있다. 그래서 Kafka Cluster에서 특정 Broker에 장애가 발생했더라도, 복구될 때 까지 정상 Broker를 활용할 수 있다.
  - 하지만 이러한 시스템을 위한 데이터 복제 과정에는 추가적인 비용이 발생한다. Producer는 모든 복제가 완료될 때까지 기다려야할까?
    - Producer, `acks = 0` => Broker에 데이터 전달되었는지 확인하지 않음. 매우 빠르지만, 데이터 유실 가능성 존재
    - `acks = 1` => leader에 전달되면 성공. follower 전달 안되면 장애 시에 유실 가능성 있으나, acks = 0보다 안전
    - `acks = all` => leader와 모든 follwer(min.insync.replicas 만큼)에 데이터 기록되면 성공. 가장 안전하지만 지연 가능성 존재
      - min.insync.replicas : 데이터 전송 성공으로 간주하기 위해 최소 몇 개의 ISR(In-Sync Replicas, leader의 데이터가 복제본으로 동기화되어 있는 follower들을 의미, acks = all 설정일 때 함께 동작)이 있어야하는지 설정
      - EX) acks = all, min.insync.replicas = 2, replication factor = 3 => 각 partition은 3개로 복제 되어야 하지만, Producer는 2개의 데이터만 확실하게 쓰면 성공 응답을 받는다. 2개(min.insync.replicas)의 복제는 동기적으로 확인, 3개(replication factor)의 복제는 비동기적으로 확인
      - 따라서 만약, Broker 2(topic1, partition2)에서 장애가 생기는 경우, Broker 1, 3겡 복제되어 있기 때문에, 정상 Broker에서 leader를 재선출하고, 이벤트 생산 및 소비를 계속 처리한다. 또한 min.insync.replicas = 2 이기 때문에, Producer는 Broker1과 Broker3에 데이터 전송을 정상적으로 성공한다.
- Kafka는 데이터를 어떻게 관리할까? => Kafka는 순서가 보장된 데이터 로그를 각 topic의 partition 단위로 Broker의 디스크에 저장한다. 그리고 각 데이터는 고유한 offset을 가지고 있다. 따라서 Consumer는 offset을 기반으로 데이터를 읽어간다.
  - EX) (topic1, partition1) [0][1][2][3][4][5][6][7][8], 만약 offset을 4까지 읽었으면 그 다음에는 5부터 읽기 시작하면 된다. 만약 새로운 Consumer가 다른 목적으로 데이터를 처리해야 한다면? 새로운 Consumer는 기존의 Consumer들과 달리 다른 목적을 가지고 병렬로 데이터를 처리해야할 수도 있다. 따라서 모든 Consumer가 전역적으로 동일한 offset을 사요할 수 없다. 서로 다른 offset이 관리되어야 한다.
  - 그래서 Consumer Group이라는 개념이 나온다. offset은 Consumer Group단위로 관리가 된다. 즉, 여러 Consumer가 동일한 Consumer Group이라면, 각 topic의 각 partition에 대해 동일한 offset을 공유한다. Consumer Group을 달리하면, 별도의 목적ㅇ으로 동일한 데이터를 병렬로 처리할 수 있는 것이다.
- Broker, Topic, Partition, Consumer Group, Offset는 누가 관리하는 것일까? Zookeeper가 kafka에서 등장하는 메타데이터를 관리한다. zookeeper도 고가용성을 위해서 여러 대를 연결하여 클러스터를 이룰 수 있다. 하지만 zookeeper에 의존성이 생기므로 더욱 복잡한 구조가 된다.
  - kafka 2.8 이후 => 메타데이터 관리에 대해 kafka broker 자체적으로 관리 가능. 따라서 `KRaft모드`로 Zookeeper 의존성을 제거하여, 더 간단한 구조가 가능하다. (KRaft모드 Broker 1대 사용 예정)
- 현재 프로젝트
  - article | article-topic(article 서비스에서 생산하는 데이터, 각 서비스에서 생산한 이벤트)
  - comment | comment-topic(comment 서비스에서 생산하는 데이터, 각 서비스에서 생산한 이벤트)
  - like | like-topic(like 서비스에서 생산하는 데이터, 각 서비스에서 생산한 이벤트)
  - view | view-topic(view 서비스에서 생산하는 데이터, 각 서비스에서 생산한 이벤트)
  - hot article 서비스의 모든 서버 군은 hot article Consumer Group으로 그룹화되고, 필요한 토픽을 구독하여 데이터를 처리한다.
  - article read 서비스의 모든 서버 군도 article read Consumer Group으로 그룹화되고, 필요한 토픽을 구독한다.
  - hot article, article read 서비스는 Consumer Group을 달리하기 때문에, Producer가 생산한 이벤트를 목적에 따라 병렬로 처리할 수 있다.
- 개념 정리
  - Producer : 카프카로 데이터를 보내는 클라이언트, 데이터를 생산 및 전송, Topic 단위로 데이터 전송
  - Consumer : 카프카에서 데이터를 읽는 클라이언트, 데이터를 소비 및 처리, Topic 단위로 구독하여 데이터 처리
  - Broker : 카프카에서 데이터를 중개 및 처리해주는 애플리케이션 실행 단위, Producer와 Consumer 사이에서 데이터를 주고 받는 역할
  - Kafka Cluster : 여러 개의 Broker가 모여서 하나의 분산형 시스템을 구성한 것, 대규모 데이터에 대해 고성능, 안정성, 확장성, 고가용성 등 지원 => 데이터의 복제, 분산 처리, 장애 복구
  - Topic : 데이터가 구분되는 논리적인 단위, 게시글 이벤트를 위한 article-topic, 댓글 이벤트를 위한 comment-topic
  - Partition : Topic이 분산되는 단위, 각 Topic은 여러 개의 Partition으로 분산 저장 및 병렬 처리된다. 각 Partition 내에서 데이터가 순차적으로 기록되므로, Partition 간에는 순서가 보장되지 않는다. Partition은 여러 Broker에 분산되어 Cluster 확장성을 높인다.
  - Offset : 각 데이터에 대해 고유한 위치(데이터는 각 Topic의 Partition 단위로 순차적으로 기록되고, 기록된 데이터는 offset을 가진다.), Consumer Group은 각 그룹이 처리한 offset을 관리한다.(데이터를 어디까지 읽었는지)
  - Consumer Group : Consumer Group은 각 Topic의 Partition 단위로 offset을 관리한다.(인기글 서비스를 위한 Consumer Group, 조회 최적화 서비스를 위한 Consumer Group), Consumer Group 내의 Consumer들은 데이터를 중복해서 읽기 않을 수 없다. Consumer Group 별로 데이터를 별렬로 처리할 수 있다.
- 도커 카프카 설치 => `docker run -d --name kuke-board-kafka -p 9092:9092 apache/kafka:3.8.0`
- 도커 kfka 컨테이너 토픽 생성 => `docker exec --workdir /opt/kafka/bin/ -it kuke-board-kafka sh` 컨테이너 접속 
  - `./kafka-topics.sh --bootstrap-server localhost:9092 --create --topic kuke-board-article --replication-factor 1 --partition 3`
  - `./kafka-topics.sh --bootstrap-server localhost:9092 --create --topic kuke-board-comment --replication-factor 1 --partition 3`
  - `./kafka-topics.sh --bootstrap-server localhost:9092 --create --topic kuke-board-like --replication-factor 1 --partition 3`
  - `./kafka-topics.sh --bootstrap-server localhost:9092 --create --topic kuke-board-view --replication-factor 1 --partition 3`

### 인기글 설계
- 배치 처리 고려 => 1. 오전12시에 전날 작성된 게시글을 모두 순회 2. 각 게시글에 대해서 좋아요 수, 조회 수, 댓글 수 조회 3. 게시글의 점수를 계산 4. 모든 게시글에 대해서 상위 10건을 선정
- 배치 처리의 단점 => 대규모 데이터이기 때문에 처리가 불가능 할 수 있다. 즉, 인기글 선정을 위해 1시간 동안은 각 서비스에 많은 데이터 질의가 필요하다. 그 결과는 타 서비스 부하가 증가한다.(영향을 주는 것)
  - 따라서 배치 처리는 한계가 있고, 스트림(Stream) 처리르 알아보자. 스트림은 연속적인 데이터 흐름이다. 로그, 주식 거래 데이터와 같은 연속적으로 들어오는 데이터를 의미한다.
  - 게시글 생성/수정/삭제 이벤트, 댓글 생성/수정/삭제 이벤트, 좋아요 생성/삭제 이벤트, 조회수 집계 이벤트 => 이벤트를 실시간으로 받아서 처리하면 별도의 배치 시스템을 구축하지 않아도, 실시간으로 인기글에 필요한 점수를 계산하여 상위 10건의 데이터를 미리 생성할 수 있다.
  - 1. 인기글 선정에 필요한 이벤트를 스트림으로 받는다. 2. 실시간으로 각 게시글의 점수를 계산한다. 3. 실시간으로 상위 10건의 인기글 목록을 만든다. 4. Client는 인기글 목록을 조회힌다.
- 스트림데이터소스(게시글, 댓글, 좋아요, 조회수 서비스, 스트림데이터를 생산하는) -> 스트림데이터(각 서비스의 이벤트, 연속적으로 생성) -> 데이터처리기(데이터 가공 필터링 집계..) -> 처리결과
- 인기글 서비스는 이러한 이벤트를 받아서 인기글 선정 작업에 사용한다. 이벤트를 받을 때마다 점수를 계산하고, 상위 10건의 인기글 목록을 생성해 두는 것이다. 이렇게 선정된 인기글 데이터(처리 결과)는 어딘가에 저장되어야 한다. => 인기글은 7일간의 내용만 관리하면 되기 때문에 휘발성이다. 따라서 Redis에 저장할 것이다. Redis는 빠른 저장소의 이점과 TTL도 지원하기 때문에 지정한 보관 기간이 끝나면 자동 삭제될 수 있다.
  - 또한 Redis는 다양한 자료구조를 지원한다. Redis의 Sorted Set(=zset)자료구조는 점수를 기반으로 정렬된 집합 데이터를 관리할 수 있다. 상위 점수 10건의 게시글을 정렬 상태로 유지할 수 있는 것이다.
- 게시글/댓글/좋아요/조회수 서비스는 실시간 이벤트를 인기글 서비스로 어떻게 전달할 수 있을까? 게시글/댓글/좋아요/조회수 서비스는 인기글 서비스로 이벤트를 실시간으로 전달해야 한다. 그리고 이벤트는 대규모 데이터가 들어올 수 있다. 인기글 서비스는, 자신의 리소스에 알맞게 인기글 선정 작업을 유연하게 처리할 수 있어야 한다.
- 이러한 이벤트 전달 및 처리 과정은 대규모 데이터에 대해서 안전하고 빠르게 처리되어야 한다.
- API 방법
  - 게시글/댓글/좋아요/조회수 서비스의 데이터 변경이 생기면, 인기글 서비스로 API를 이용한 이벤트 전송, 타 서비스에 직접적 의존 + 시스템 간 결합도 증가 + 서버 부하 전파(인기글은 데이터를 실시간으로 push 받고, 게시글/댓글/좋아요/조회수는 데이터를 실시간으로 push한다. 또한 장애 전파와 데이터 유실 가능성)
- Message Broker 방법
  - 게시글/댓글/좋아요/조회수 서비스의 데이터 변경이 생기면, MQ로 이벤트를 전송하고, 인기글 서비스에서 이벤트를 가져와서 처리 + 복잡한 구현 + 타 서비스에 MQ 이용한 간접적 의존성 + 시스템 간 결합도 감소 + 서버 부하 전파(인기글 서비스는 MQ에서 적잘하게 이벤트를 가져와서 비동기 처리가 가능하고, 게시글/댓글/좋아요/조회수는 MQ로 이벤트 전송만 하면되고, 장애 전파와 데이터 유실 가능성이 낮다.)

### MQ로서 Kafka
- `게시글/댓글/좋아요/조회수 서비스, Producer` => `게시글/댓글/좋아요/조회수 이벤트` => `Kafka` <= `인기글 서비스, Consumer` => `Redis(인기글 데이터)`
- 이벤트를 주고받으며 MSA간에 통신하는 것을 `Event Driven Architecture`라고 한다.
- 실시간으로 들어오는 이벤트를 기반으로, 어떻게 인기글을 미리 생성?

### 인기글 서비스가 인기글 데이터를 만드는 과정
- `20241001`에 작성된 모든 데이터가 `key=20241001` 형태로 Redis에 들어가고 자료구조 형태는 Sorted Set(zset) 자료구조를 사용한다. 각 게시글의 점수를 기반으로 정렬된 상태를 가진다.(data = 게시글ID, score = 게시글 점수). 이를 이용해서 Sorted Set에 상위 10건의 데이터만 유지할 수 있다.
- 2024 10월 2일 00시가 되면, `key=20241002` 의 Sorted Set에 삽입된다. 이제 `20241001` 데이터는 건드릴 필요는 없다.
- 2024 10월 2일 오전 1시가 되면, 10월 1일의 인기글은 사용자들에게 제공될 수 있다. 오늘 제공해야 하는 인기글을 어제 미리 실시간으로 만들고 있었기 때문에, 배치 적업은 필요하지 않다. Client가 조회해야하는 데이터만 지정하면 된다.
- 이러한 인기글 내역은 최근 7일까지 관리 및 조회할 수 있으면 충분하다.(TTL 활용)

### 인기글 서비스 점수 계산
- 인기글 서비스는 다른 서비스로부터 이벤트를 개별적으로 받는다. 즉, 현재의 좋아요 수나 이런 것들이 이벤트 정보에 포함되지 않는 다는 것이다. 따라서 다른 데이터에 대해 현재의 상태를 알아야 한다. 이러한 데이터를 받아오기 위해 각 서비스에 API 요청을 할 수 있지만 다른 서비스와 결합을 하기 때문에 인기글 작업으로 각 서비스에 부하가 전달된다.
- 다른 서비스가 인기글을 위한 데이터를 생산하고 관리할 책임은 없다. 즉, 이러한 데이터를 생산하고 관리할 책임은 인기글에게 있는 것이다. 따라서 인기글 서비스가 인기글에 피룡한 데이털를 자체적으로 보관하는 케이스를 생각하자. 이벤트로 전달 받은 우너본 데이터를 기반으로 인기글 선정에 필요한 데이터를 자체적으로 가공 및 생산할 수도 있다. 다른 서비스에 의존하지 않고, 독립적으로 인기글 기능을 제공해나갈 수 있는 것이다.
- 따라서 점수 계산에 필요한 데이터를 실시간으로 다시 요청하지 않고, 자체적인 데이터를 가지도록 만들자. 이러한 데이터는 하루만 보관하면 되므로, 용량이 크진 않지만 접근이 빠르고 휘발성을 가지는 Redis를 사용하자.

### Transactional Messaging Queue
- 데이터 일관성, 원자성 관리를 위해 트랜젝션 관리는 중요하다.
- Producer(비즈니스 로직, 게시글/댓글/좋아요/조회수 서비스) => 이벤트 전송(게시글/댓글/좋아요/조회수 이벤트) => Kafka <= 이벤트 처리 <= Consumer
- Consumer에서 이벤트 처리를 정상적으로 모두 완료한 이후에 offset을 commit 하면, 데이터의 유실 없이 처리할 수 있다.
- 또한 Producer에서 kafka로 이벤트를 전송하는 과정이 장애가 생겼다고 해도, Producer내부의 비즈니스 로직 수행은 롤백되면 안된다. 따라서 이런 상황에서는, 게시글은 정상 생성되었더라도, 이러한 이벤트를 Kafka에 전달할 수 없는 상황이다.
- 신뢰할 수 있는 시스템인 Kafka로 아직 데이터가 전송되지 못했기 때문에, Producer가 생산 및 전파해야 하는 이벤트 데이터는 유실될 수 밖에 없다. 이로 인해 각 서비스마다 데이터의 일관성이 깨지게 된다. Producer에서 게시글이 생성되었는데, Consumer에서 게시글 생성 사실을 전달 받지 못하는 상황이 되는 것이다.
- 이 문제를 비즈니스 로직 수행과 이벤트 전송이 하나의 트랜잭션으로 처리되어야 한다. 이러한 보장은 꼭 실시간으로 처리될 필요는 없다. 비즈니스 로직은 우선적으로 처리되더라도, 이벤트 전송은 장애가 해소된 이후 뒤늦게 처리되어도 충분할 수 있다.(Eventually Consistency)
- 트랜잭션 순서 고민
  - ```sql
    transaction start
    비즈니스로직수행
    commit or roll back
    
    transaction start
    비즈니스로직수행
    publishEvent()
    commit or roll back
    -- 지금까지 사용하던 트랜잭션은 MySQL의 단일 DB(1개의 샤드)에 대한 트랜잭션이다. 
    -- MySQL의 상태변경과 Kafka로의 데이터 전송을, MySQL의 트랜잭션 기능을 이용해서 단일 트랜잭션으로 묶을 수 없다. 왜냐하면 MySQL과 Kafka는 서로 다른 시스템이기 때문이다.
    -- 만약, publishEvent()에서 3초간의 장애가 생기는 경우, Kafka의 장애가 서버 애플리케이션과 MySQl로 장애가 전파될 수도 있다. 또는 트랜잭션 commit이 실패했는데, 이벤트 전송은 이미 완료 됐을 수 있다.
    -- 만약, 3번 과정을 비동기로 처리한다면? 비동기로 처리된 이벤트 전송이 실패한다고 해서, MySQL의 트랜잭션이 자동으로 롤백되진 않는다. 롤백을 위한 보상 트랜잭션을 직접 수행할 수 있지만 복잡하다.
    ```
- 2개의 다른 시스템을 어떻게 단일한 트랜잭션으로 묶을까? 분산 시스템 간의 트랜잭션 관리가 필요하다. Distributed Transaction
- Transactional Messaging : 메시지 전송과 타 시스템 작업 간에 분산 트랜잭션을 보장하는 방법
- Transactional Messaging 을 달성하기 위한 분산 트랜잭션의 방법들
  - `Two Phase Commit`
    - 분산 시스템에서 모든 시스템이 하나의 트랜잭션을 수행할 때, 모든 시스템이 성공적으로 작업을 완료하면 commit, 1개라도 실패하면 rollback
    - `Prepare phase(준비 단계)`
      - Coordinator는 각 참여자에게 트랜잭션 커밋할 준비가 되었는지 물어본다. 각 참여자는 트랜잭션을 커밋할 준비가 되었는지 응답한다.
    - `Commit phase(커밋 단계)`
      - 모든 참여자가 준비 완료 응답을 보내면, Coordinator는 모든 참여자에게 트랜잭션 커밋을 요청한다. 모든 참여자는 트랜잭션을 커밋한다.
    - 문제점
      - 모든 참여자의 응답을 기다려야 하기 때문에 지연이 길어진다. 또한 Coordinator 또는 참여자 장애가 발생하면, 참여자들은 현재 상태를 모른 채 대기해야 한다. 또한 트랜잭션의 복구 처리가 복잡해질 수 있다.
      - 성능 및 오류 처리의 복잡성 문제가 있고, Kafka와 MySQL은 자체적으로 이러한 방식의 트랜잭션을 지원하지 않는다. 따라서 Two Phase Commit은 Transactional Messaging을 달성하기에 적절하지 않다.
  - `Transactional Outbox`
    - 이벤트 전송 작업을 일반적인 DB 트랜잭션에 포함할 수 없다. 하지만, 이벤트 전송 정보를 DB 트랜잭션에 포함하여 기록할 수 있다.
    - 트랜잭션을 지원하는 DB에 Outbox 테이블을 생성하고, 서비스 로직 수행과 Outbox 테이블 이벤트 메시지 기록을 단일 트랜잭션으로 묶는다.
    - ```
      1. 비즈니스 로직 수행 및 Outbox 테이블 이벤트 기록
        1. transaction start
        2. 비즈니스 로직 수행
        3. Outbox 테이블에 전송 할 이벤트 데이터 기록
        4. commit or abort
      2. Outbox 테이블을 이용한 이벤트 전송 처리
        1. Outbox 테이블 미전송 이벤트 조회
        2. 이벤트 전송
        3. Outbox 테이블 전송 완료 처리
      ```
    - 데이터 생성/삭제/수정 요청 => Client => Data Table 상태 변경 + Outbox Table 이벤트 삽입 => DB(Transaction[Data Table, OutboxTable]) <= Message Relay => 이벤트 전송 => Message Broker
      - Message Relay는 이벤트 전송이 정상적으로 완료되면, Outbox 테이블의 이벤트를 완료 상태로 변경한다.
    - Message Relay : Outbox Table에서 미전송 이벤트를 조회 + Message Broker로 이벤트를 전송하는 역할
    - Two Phase Commit의 성능과 오류 처리에 대한 문제가 줄어든다. 또한 DB 트랜잭션 커밋이 완료되었다면, Outbox 테이블에 이벤트 정보가 함께 기록되었기 때문에, 이벤트가 유실되지 않는다.
    - 추가적인 Outbox 테이블 생성 및 관리가 필요하다. Outbox 테이블의 미전송 이벤트를 Message Broker로 전송하는 작업이 필요하다.
    - 어떻게 Message Broker로 이벤트를 전송하는 작업을 할까? => 이벤트 전송 작업을 처리하기 위한 시스템 구축 or Transaction Log Tailing Pattern 활용
  - `Transaction Log Tailing`
    - DB의 트랜잭션 로그를 추적 및 분석하는 방법. DB는 각 트랜잭션의 변경 사항을 로그로 기록한다. => MysQL binlog, PostgreSQL WAL, SQL Server Transaction Log ..
    - 이러한 로그를 읽어서 Message Broker에 이벤트를 전송해볼 수 있다. => CDC(Change Data Capture) 기술을 활용하여 데이터의 변경 사항을 다른 시스템에 전송한다. / 변경 데이터 캡처 : 데이터 변경 사항을 추적하는 기술
    - 데이터 생성/삭제/수정 요청 => Client => Data Table 상태 변경 + Outbox Table 이벤트 삽입 => DB(Transaction[Data Table, OutboxTable], Transaction Log) <= Transaction Log 조회 <= Transaction Log Miner => 이벤트 전송 Message Broker
      - 만약, Data Table을 직접 추적한다면, Outbox Table 미사용할 수 있다. 즉, 데이터 변경에 대한 것을 직접 추적하면 된다.
    - DB에 저장된 트랜잭션 로그를 기반으로, Message Broker로의 이벤트 전송 작업을 구축하기 위한 방법으로 활용될 수 있다. Data Table을 직접 추적하면, Outbox Table은 미사용 할 수도 있다.
    - 트랜잭션 로그를 추적하기 위해 CDC 기술을 활용해야 한다. 이 경우는 추가적인 학습 및 운영 비용이 생긴다.
- Transaction Outbox 사용
  - Outbox Table의 필요성 => Transaction Log Tailing을 활용하면 Data Table의 변경 사항을 직접 추적할 수 있다. (Outbox Table이 필요하지 않을 수 있다.) 하지만, Data Table은 메시지 정보가 DB 변경 사항에 종속된 구조로 한정 된다.
  - Outbox Table을 활용하면, 부가적인 테이블로 인한 복잡도 및 관리 비용은 늘어나지만, 이벤트 정보를 더욱 구체적ㅇ이고 명확하게 정의할 수 있다.
  - 데이터의 변경 사항과 Outbox Table로의 이벤트 데이터 기록을 MySQL의 단일 트랜잭션으로 처리한다.
  - 이벤트 전송이 필요한 Article, Comment, Like, View 서비스는 트랜잭션을 지원하는 MySQL을 사용하고 있기 때문에, Outbox 테이블과 트랜잭션을 통합하여 구현할 수 있다.
- Transaction Outbox 설계
  - 게시글 생성/수정/삭제 API => Article Service => Article Table 상태변경 + Outbox Table 이벤트 삽입 => MySQL(Transaction[Article Table, Outbox Table]) <= 미전송 이벤트 조회(Outbox Table, 10초 간격 polling) <= Ressage Relay => 이벤트 전송 => Kafka
    - Message Relay가 Outbox Table을 10초 간격으로 polling 한다고 해도, 여전히 지연은 크다. 게시글 서비스에서 트랜잭션이 commit되면, Message Relay로 이벤트를 즉시전달하자. Message Relay는 전달 받은 이벤트를 비동기로 카프카에게 전송할 수 있다.
    - Message Relay는 전송이 실패한 이벤트에 대해서만 Outbox Table에서 polling하면 된다. 그렇지 않으면, Message Relay로의 이벤트 전달될 수 있는 지점이 2개이므로 각 이벤트가 중복 처리될 수 있다.
    - 실패한 이벤트는 장애 상황에만 발생할 것이고, 정상 상황에서는 10초 정도면 이벤트 전송할 시간으로 충분 했을 것이다. 생성된 지 10초가 지난 이벤트만 polling하자. 물론 여전히 이벤트는 중복 처리될 수 있으므로 Consumer 측에서 멱등성을 고려한 개발은 필요하다.
    - 전송이 완료된 이벤트는 간단하게 Outbox Table에서 삭제할 것이다. 물론 이벤트를 비즈니스 관점에서 유의미할 수 있고, 이후에도 추적 또는 리플레이가 필요한 상황도 있다.(Event Sourcing)
    - 게시글 서비스의 여러 서버 애플리케이션에서 동시에 처리될 수 있고, DB 샤딩이 고려된 분산 시스템이다. Message Relay가 Outbox Table의 미전송 이벤트를 polling하는 작업은 어떻게 처리될 수 있을까?
      - 미전송 이벤트를 polling하는 것은 특정한 샤드 키가 없으므로, 모든 샤드에서 직접 polling해야 한다. 모든 애플리케이션이 동시에 polling하면, 동일한 이벤트를 중복으로 처리할 수도 있고, 각 애플리케이션마다 모든 샤드를 polling 하면, 처리에 지연이 생길 수 있다.
      - 따라서 각 애플리케이션이 처리할 샤드를 중복 없이 적절히 분산할 필요가 있다. => 각 애플리케이션은 샤드의 일부만 할당 받아서 처리할 수 있도록 해보자. 이러한 할당은 Message Relay 내부의 Coordinator가 처리한다.
      - Coordinator는 자신을 실행한 APP의 식별자와 현재 시간으로, 중앙 저장소에 3초 간격으로 ping을 보낸다. 이를 통해 Coordinator는 실행 중인 APP 목록을 파악하고, 각 APP에 샤드를 적절히 분산한다.
      - 중앙 저장소는 마지막 ping을 받은지 9초가 지났으면 APP이 종료되었다고 판단하고 목록에서 제거한다. 중앙 저장소는 Redis의 Sorted Set을 이용한다. APP의 식별자와 마지막 ping 시간을 정렬된 상태로 저장해둘 수 있다.
      - 우리는 샤딩을 직접 구현하진 않았지만, 4개의 샤드가 있다고 가정한다. Coordinator는 nrodml APP에 4개의 새드를 범위 기반(Range-Based)으로 할당한다. 예를 들어, 2개의 APP이 있다면, 0 ~ 1번 샤드와 2 ~ 3번 샤드를 각각 polling한다.
      - 이러한 Message Relay는 모듈로 구현한다. 게시글/댓글/좋아요/조회수 서비스는 Message Relay Module 의존성만 추가하면, Transactional Messaging을 쉽게 구현할 수 있다.

## 7. 게시글 조회 최적화
- 게시판 서비스의 특성
  - 서비스 특성 상, 읽기 트래픽 > 쓰기 트래픽
  - 사용자에게 게시글만 보여주진 않는다 => 게시글, 좋아요 수, 댓글 수, 조회 수, 작성자 정보 => 각 데이터가 분산되어 있는 상황에서 어떻게 조회?
  - 먼저 생각나는 것은 Article에서 Comment, Like, View로 데이터 요청 후, Article에서 데이터를 조인한다. 문제는 없어보인다.
    - Article은 읽기와 쓰기가 함께 처리되고, 읽기 트래픽 > 쓰기 트래픽 => 만약, 읽기 트래픽으로 인해 서버를 증설하는 경우에는 필요없는 쓰기 작업에 대한 리소스도 함께 확장해야 한다. => 읽기/쓰기 작업 특성에 의해 리소스가 낭비되는 지점이 생기는 것
    - 지금까지는 Article과 Comment, Like, View는 의존성이 없지만, 게시글 조회가 생기는 경우, 각 서비스는 의존성이 생긴다. 의존성의 방향이 단방향이 아니라 각 서비스끼리 양방향이기 때문에 순환참조가 발생할 수 있다. => comment, like, view는 articleId를 가지고 있다.
    - 따라서 각 서비스는 데이터의 무결성을 검증하기 위해, 게시글 유효성 확인이 필요하다. comment/like/view는 데이터의 무결성을 검증하려면 게시글에 종속되어 있기 떄문에 article로 게시글 데이터 요청이 필요하다. => 따라서 단순히 article가 각 서비스에 데이터를 요청해서 조합하면, MSA간의 순환참조가 발생하는 것이다.
    - 순환참조 문제는 각 MSA가 독립적 배포 및 유지보수 될 수 없게 만들고, 서로 간에 장애 전파될 수 있으며, 테스트에도 어려움이 생긴다.
  - 이런 문제를 해결하기 위해서, 게시글 조회를 위한 서비스를 만든다. Client는 게시글 조회 서비스로 데이터를 요청하고, 게시글 조회 서비스는 각 MSA로 개별 데이터를 요청하여 조합 및 응답한다.
    - client -> Article Read Service -> Article/Like/View/Comment Service
    - 양방향 의존성을 끊으면서, 순환참조 문제가 해결된다. 각 MSA는 다시 독립적으로 관리될 수 있다. 또 게시글 조회를 위한 MSA이기 때문에, 읽기 트래픽에 대해서만 독립적으로 확장이 가능하다.
    - 여기서도 문제가 발생한다. 각 데이터가 여러 서비스 또는 DB에 분산되어 있기 때문에, 데이터를 요청하기 위한 네트워크 비용, 각 서비스에 부하 전파, 데이터 조합 및 질의 비용이 증가한다.
    - 따라서 이 문제는 CQRS로 해결하자

### CQRS
- Command Query Responsibility Segregation
- Command와 Query의 책임을 분리 + 데이터에 대한 변경(command)과 조회(Query) 작업을 구분하는 패턴
- 클래스 레벨에서, 패키지 레벨에서, 서비스 레벨에서, 데이터 저장소 레벨에서 => 좁은 또는 넓은 범위에서 이러한 책임 분리가 일어날 수 있다.
- Client => `Command`(CREATE/DELETE/UPDATE), `Query`(READ) => 게시글/좋아요/댓글 Command는 기존의 게시글/댓글/좋아요 서비스, 게시글/좋아요/댓글 Query는 신규로 만드는 게시글 조회 서비스 => MSA레벨에서 분리하는 것
- 게시글 조회 서비스는 어떻게 게시글/댓글/좋아요 서비스에서 데이터를 가져올까? => Command 서버에서 실시간으로 가져오면, 결국 Command 서버로 부하가 전파되는 문제가 있다. 게시글 조회 서비스에 자체 DB를 구축하고 데이터를 채우자. 데이터 저장소 레벨에서 분리하는 것이다.
- 그러면 Query DB는 실시간 변경 사항을 어떻게 가져올까? => API로 주기적인 변경 사항을 polling 또는 `Message Broker`를 활용하기
- 이벤트는 각 Producer에서 이미 Kafka로 전송되고 있기 때문에, 게시글 조회 서비스는 Consumer Group만 달리하여 그래도 활용 할 수 있다.
  - `게시글/댓글/좋아요(Producer)` -> 이벤트전송 -> Kafka <- `게시글조회서비스(데이터베이스, Consumer)`
  - Client -> 게시글/댓글/좋아요(CREATE/DELETE/UPDATE, READ)
- 게시글조회서비스는 DB에서 데이터를 어떻게 관리할 수 있을까? 게시글/댓글/좋아요 서비스의 데이터 모델과 동일하게 관리해야 할까?
  - 데이터 모델은 반드시 Command 서버와 동일할 필요는 없다. 분산된 데이터를 어차피 조합하여 질의해야 하기 때문에, Query에서는 조인 비용을 줄이고, 조회 최적화를 위해 필요한 데이터가 비정규화된 Query 모델을 만들어보자.
    - Query Model = 게시글 + 댓글 수 + 좋아요 수
    - Article Query Model => article, comment_count, like_count
  - Query Model 단건만 조회하면, 필요한 데이터를 모두 조회할 수 있다.
- Query의 DB는 어떤 것을 사용할까? => In memory Redis를 사용하자
  - 빠른 성능으로 데이터를 제공 + Redis는 메모리 기반이기 때문에 디스크에 비해 용량 대비 가격이 비싸다.
  - 하지만 게시판 시스템 특성 상, 최신글이 조회되는 경우가 많다. Redis에 TTL(1일)을 설정하여, 24시간 이내의 최신글만 Redis에 보관한다.
- Query DB(Redis)에서 데이터가 만료되었으면? => Command 서버로 원본 데이터를 다시 요청해서 Query Model을 만들 수 있다. 만료된 데이터만 간헐적으로 요청하기 때문에, 트래픽이 크지는 않다.
  - 또한 Query DB의 장애 상황, 이벤트 누락, 데이터 유실 등 상황에 원본 데이터 서버로 질의의하여 가용성을 높일 수 있다.
  - Comsumer(게시글조회서비스, Redis) --> Producer(게시글/댓글/좋아요서비스)
- 조회수는 Query Model에 왜 비정규화되지 않았을까?
  - 조회수는 읽기 트래픽에 따라서 올라가는 데이터 특성이다. 읽기 트래픽에 의해 쓰기 트래픽도 발생할 수 있는 상황, 따라서 조회수의 변경마다 Query Model을 만드는게 오히려 비효율적이다. 또한 조회수는 조회수 서비스에서도 빠른 저장소(Redis)에 이미 저장되어 있다. 그리고 조회수 이벤트는 백업 시점(100개 단위)에만 전송(실시간 데이터 아님)
  - 여기에서 조회수는 조회수 서비스로 직접 요청해서 가져와보자 => 대신 게시글 조회 서비스에서 짧은 만료 시간이라도 캐시해서 부하를 줄여보자. 이런 캐시는 캐시 최적화 전략에서 더욱 개선할 것임

### 게시글 목록 조회 최적화 전략
- 게시글 서비스의 MySQL DB는 모든 게시글 목록 조회 트래픽을 받아주고 있다. 물론, 인덱스를 활용하여 쿼리 최적화는 되어있다.
- 위 DB의 부하를 줄이면서, 조회 성능을 높이는 방법은? => 이번에도 Redis 캐시를 고려하자. 원본 DB의 부하를 줄이고, 조회 성능을 높인다.
- 일반적으로 적용할 수 있는 캐시 전략(@Cacheable)
  - 1. 캐시에서 key를 기반으로 데이터 조회
  - 2. 캐시 데이터 유무에 따라, 데이터가 있으면 캐시의 데이터를 응답, 데이터가 없으면 원본 데이터를 가져오고 캐시에 저장한 뒤 응답
  - 문제점
    - 목록 데이터의 조회 parameter => boardId, page, pageSize(페이지번호방식) / boardId, lastArticleId, pageSize(무한스크롤)
    - 위 파라미터가 캐시의 키, 조회된 게시글 목록이 값이 될텐데, 단순히 키/값 저장 전략을 취하면 캐시 효과를 볼 수 있을까? 게시글 작성/삭제되면, 해당 키로 만들어진 게시글 목록은 과거의 데이터가 된다.
      - 게시글 목록이 실시간으로 변경되어 있는데 목록 데이터는 key/value 고정 값으로 반영되어 있다.
  - 따라서, 게시글이 작성/삭제될 때마다, 새로운 목록을 보여주려면 캐시 만료가 필요하다. 캐시 만료가 잦고 캐시 히트율이 낮아진다.
  - 캐시 만료를 임의로 늘린다면? 캐시가 원본 데이터와 동기화 되어있지 않기 때문에, 과거 데이터가 노출된다.
  - 최신 데이터를 반영하면서 캐시 히트율을 높일 수는 없을까?
- 캐시 데이터를 실시간으로 미리 만들어두면 어떨까? 게시글 생성/삭제되면 캐시에 미리 목록 데이터를 생성해둔다. 인기글 데이터를 미리 생성해 두던 것처럼. 게시글 조회 서비스는 이미 게시글 서비스에서 이벤트를 받고 있다.
- 하지만 memory는 disk에 비해 비싼 저장소이기 때문에 모든 데이터를 memory에 저장하기는 힘들다. 모든 데이터를 캐시할 필요가 있을까? 게시판의 사용패턴을 보자.
  - 특정 게시판을 클릭하면, 게시판의 첫 페이지로 이동하여 최신글 목록이 조회된다. 게시판의 첫 페이지에 나타나는 최신의 게시글이 가장 많이 조회될 수 밖에 없는 것이다. 뒷 페이지로 명시적으로 이동하지 않는 이상 과거 데이터는 비교적 조회 횟수가 적어진다.
  - 즉, 모든 데이터를 캐시할 필요는 없다.
  - hot data : 자주 조회되는 데이터(최신의 글)
  - cold data : 자주 조회되지 않는 데이터(과거의 글)
  - hot data에만 캐시를 적용해도 충분하지 않을까? 게시글 조회 서비스의 redis는 최신 1000개의 목록만 캐시, 1000개 이후의 데이터는 게시글 서비스로 요청
- 게시글 조회 서비스는 kafka로부터 게시글 생성/삭제 이벤트를 전달 받는다. // 게시글 서비스 => 이벤트 전송 => Kafka <= 게시글 조회 서비스 => Redis
  - 게시글 조회 서비스는 Kafka로부터 전달 받은 게시글 생성/삭제 이벤트로, Redis에 게시판별 게시글 목록을 저장한다. sorted set 자료구조를 활용하여 최신순 정렬을 1000개까지 유지한다. data = article_id, score = article_id(생성시간)
  - Client가 게시글 목록을 요청한다면? 최신글 1000건 이내의 목록 데이터는 게시글 조회 서비스의 Redis에서 가져오고, 이후의 데이터는 게시글 서비스에서 가져올 수 있다.