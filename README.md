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